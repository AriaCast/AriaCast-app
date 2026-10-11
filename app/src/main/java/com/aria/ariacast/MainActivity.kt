package com.aria.ariacast

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var audioCastService: AudioCastService? = null
    private var isBound = false
    private var selectedServers: List<Server> = emptyList()
    private var isUserSelecting = false

    private lateinit var stateTextView: TextView
    private lateinit var castButton: MaterialButton
    private lateinit var discoveryButton: MaterialButton
    private lateinit var serverRecyclerView: RecyclerView
    private lateinit var permissionButton: MaterialButton
    private lateinit var statusCard: MaterialCardView
    private lateinit var groupsSection: LinearLayout
    private lateinit var groupRecyclerView: RecyclerView
    private lateinit var addGroupButton: MaterialButton
    private lateinit var syncSection: LinearLayout
    private lateinit var syncSliderContainer: LinearLayout
    lateinit var pluginContainer: LinearLayout

    lateinit var discoveryManager: DiscoveryManager
    private lateinit var serverListAdapter: ServerAdapter
    private lateinit var groupListAdapter: GroupAdapter
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var pluginManager: PluginManager
    private lateinit var updateManager: UpdateManager

    private var currentAccentColor: Int = R.color.accent_blue
    private var currentThemeMode: Int = ThemeUtils.MODE_NIGHT_FOLLOW_SYSTEM
    private var lastPluginsUpdateTime: Long = 0

    private val _audioCastServiceFlow = MutableStateFlow<AudioCastService?>(null)
    val audioCastServiceFlow = _audioCastServiceFlow.asStateFlow()

    private val _refreshTrigger = MutableStateFlow(0)
    
    private val activeTouchHosts = mutableSetOf<String>()

    private var currentCardAnimator: ValueAnimator? = null

    // Bound to the current connection's lifetime, not the Activity's - onServiceConnected
    // fires again on every rebind (e.g. each time the app comes back to the foreground),
    // and without cancelling the previous one here each rebind piled on another live
    // collector on the same StateFlow that never got torn down.
    private var serviceStateJob: Job? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as AudioCastService.AudioCastBinder
            val s = binder.getService()
            audioCastService = s
            _audioCastServiceFlow.value = s
            isBound = true
            serviceStateJob?.cancel()
            serviceStateJob = lifecycleScope.launch {
                s.state.collectLatest { state ->
                    updateUi(state)
                    updateSyncUi()
                }
            }
            pluginManager.runEnabledPlugins(this@MainActivity, s)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            audioCastService = null
            _audioCastServiceFlow.value = null
            isBound = false
            serviceStateJob?.cancel()
            serviceStateJob = null
        }
    }

    private val startMediaProjection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK && it.data != null) {
            if (selectedServers.isNotEmpty()) {
                val serviceIntent = Intent(this, AudioCastService::class.java).apply {
                    action = AudioCastService.ACTION_START
                    putExtra(AudioCastService.EXTRA_MEDIA_PROJECTION_TOKEN, it.data)
                    
                    if (selectedServers.size == 1) {
                        val server = selectedServers[0]
                        putExtra(AudioCastService.EXTRA_SERVER_HOST, server.host)
                        putExtra(AudioCastService.EXTRA_SERVER_PORT, server.port)
                        putExtra(AudioCastService.EXTRA_SERVER_NAME, server.name)
                        putExtra(AudioCastService.EXTRA_SERVER_PLATFORM, server.platform)
                        putExtra("com.aria.ariacast.EXTRA_SERVER_EXTRA", server.extra)
                    } else {
                        val array = JSONArray()
                        selectedServers.forEach { s ->
                            array.put(JSONObject().apply {
                                put("name", s.name)
                                put("host", s.host)
                                put("port", s.port)
                                put("platform", s.platform)
                                put("extra", s.extra)
                            })
                        }
                        putExtra(AudioCastService.EXTRA_SERVERS_JSON, array.toString())
                    }
                }
                ContextCompat.startForegroundService(this, serviceIntent)
            }
        } else {
            Toast.makeText(this, getString(R.string.media_projection_denied), Toast.LENGTH_SHORT).show()
        }
    }

    private var wifiAddNetworkContinuation: CancellableContinuation<Boolean>? = null

    /** Result of the Settings.ACTION_WIFI_ADD_NETWORKS dialog launched from
     *  [joinDeepLinkWifi] - see WifiJoinManager for why this doesn't try to hold or
     *  release the network itself. */
    private val requestAddWifiNetwork = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val saved = WifiJoinManager.interpretResult(result.resultCode, result.data)
        wifiAddNetworkContinuation?.let { if (it.isActive) it.resume(saved) }
        wifiAddNetworkContinuation = null
    }

    private val requestCastPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val recordAudioGranted = grants[Manifest.permission.RECORD_AUDIO]
            ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        if (recordAudioGranted) {
            startMediaProjection.launch(mediaProjectionManager.createScreenCaptureIntent())
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // System won't show the request dialog again (permanently denied, or a device
            // policy) - re-requesting from here on is a silent no-op, so the only way
            // back in is the app's own Settings page.
            showOpenSettingsForPermissionDialog()
        } else {
            Toast.makeText(this, getString(R.string.record_audio_permission_required), Toast.LENGTH_LONG).show()
        }
    }

    private fun showOpenSettingsForPermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.record_audio_permission_required))
            .setMessage(getString(R.string.record_audio_permission_permanently_denied))
            .setPositiveButton(getString(R.string.settings)) { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun beginCast() {
        val permissionsNeeded = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissionsNeeded.isNotEmpty()) {
            requestCastPermissions.launch(permissionsNeeded.toTypedArray())
        } else {
            startMediaProjection.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
    }

    fun castToServers(servers: List<Server>) {
        selectedServers = servers
        val companionEnabled = sharedPreferences.getBoolean(AriaCompanionActivity.KEY_COMPANION_ENABLED, false)
        val companionIp = sharedPreferences.getString(AriaCompanionActivity.KEY_COMPANION_IP, null)
        if (companionEnabled && !companionIp.isNullOrEmpty()) {
            launchCompanionCast()
        } else {
            beginCast()
        }
    }

    private fun launchCompanionCast() {
        // AriaCompanion's ESP32 board only speaks AriaCast's native protocol
        // directly to a receiver, so only those destinations are usable here
        // even if a multiroom group snuck in an AirPlay/DLNA/Google Cast host.
        val ariaCastServers = selectedServers.filter { it.platform == "AriaCast" }
        val target = ariaCastServers.firstOrNull()
        if (target == null) {
            Toast.makeText(this, getString(R.string.companion_needs_ariacast_receiver), Toast.LENGTH_SHORT).show()
            return
        }

        val serviceIntent = Intent(this, AudioCastService::class.java).apply {
            action = AudioCastService.ACTION_START_COMPANION
            putExtra(AudioCastService.EXTRA_SERVER_HOST, target.host)
            putExtra(AudioCastService.EXTRA_SERVER_PORT, target.port)
            putExtra(AudioCastService.EXTRA_SERVER_NAME, target.name)
            putExtra(AudioCastService.EXTRA_SERVER_PLATFORM, target.platform)
            putExtra(AudioCastService.EXTRA_SERVER_EXTRA, target.extra)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    /**
     * Handles ariacast://<host>[:<port>][?type=<type>&name=<name>][&ssid=<ssid>&pass=<pass>]
     * links (e.g. from an NFC tag). type defaults to "ariacast" (AriaCast's own protocol)
     * when omitted, matching a bare `ariacast://host` link. Tapping the same link again
     * while already casting to that host stops casting instead of restarting it.
     *
     * ssid/pass ask the phone to save and join that Wi-Fi network first via Android's own
     * "save this network?" dialog (see WifiJoinManager) - e.g. a tag that should both get
     * the phone onto the receiver's network and start casting to it. That has to happen
     * before anything else here: discovery (mDNS/SSDP) can't find a device on a network the
     * phone isn't on yet, so a wifi-joining link skips discovery-based resolution entirely
     * and connects directly using the target's default port, then waits for that address to
     * actually become reachable before casting to it.
     */
    private fun handleDeepLink(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (uri.scheme?.equals("ariacast", ignoreCase = true) != true) return
        // Consume it so a later onCreate/onNewIntent replay (e.g. a config change) doesn't
        // re-trigger the same cast/stop action a second time.
        intent.data = null

        val host = uri.host
        if (host.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.deep_link_invalid), Toast.LENGTH_SHORT).show()
            return
        }
        val requestedPort = uri.port // -1 when not specified
        val platform = platformForDeepLinkType(uri.getQueryParameter("type"))
        val name = uri.getQueryParameter("name")
        val label = name ?: host
        val ssid = uri.getQueryParameter("ssid")

        lifecycleScope.launch {
            val service = waitForServiceConnection()
            val activeHost = service?.activeDestinations?.value?.firstOrNull()?.host
            if (service?.state?.value == CastState.CASTING && activeHost == host) {
                Toast.makeText(this@MainActivity, getString(R.string.deep_link_stopping, label), Toast.LENGTH_SHORT).show()
                startService(Intent(this@MainActivity, AudioCastService::class.java).apply {
                    action = AudioCastService.ACTION_STOP
                })
                return@launch
            }

            if (!ssid.isNullOrEmpty()) {
                val saved = joinDeepLinkWifi(ssid, uri.getQueryParameter("pass") ?: "")
                if (!saved) {
                    Toast.makeText(this@MainActivity, getString(R.string.deep_link_wifi_failed, ssid), Toast.LENGTH_LONG).show()
                    return@launch
                }
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
            isUserSelecting = true
            castToServers(listOf(target))
        }
    }

    private suspend fun waitForServiceConnection(timeoutMs: Long = 3000): AudioCastService? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (audioCastService == null && System.currentTimeMillis() < deadline) delay(100)
        return audioCastService
    }

    /** Launches Android's native "save this Wi-Fi network?" dialog and suspends until the
     *  user responds. See WifiJoinManager for why the app doesn't hold or release this
     *  network itself once saved. */
    private suspend fun joinDeepLinkWifi(ssid: String, password: String): Boolean =
        suspendCancellableCoroutine { cont ->
            wifiAddNetworkContinuation = cont
            cont.invokeOnCancellation { wifiAddNetworkContinuation = null }
            requestAddWifiNetwork.launch(WifiJoinManager.buildAddNetworkIntent(ssid, password))
        }

    /**
     * Resolves a deep link into a full [Server]. Normally an already-discovered device is
     * preferred - it carries metadata a blind connection can't (DLNA's SSDP-sourced control
     * URLs, an AirPlay 2 device's pairing key) - falling back to a direct connection using
     * the platform's well-known default port. When [skipDiscovery] is set (a Wi-Fi-joining
     * link), discovery is skipped entirely and this only ever returns a direct connection.
     */
    private suspend fun resolveDeepLinkTarget(host: String, requestedPort: Int, platform: String, name: String?, skipDiscovery: Boolean = false): Server? {
        fun directConnect(): Server? {
            val blindPort = if (requestedPort > 0) requestedPort else defaultPortForDeepLinkPlatform(platform)
            if (blindPort <= 0) return null
            return Server(
                name = name ?: host,
                host = host,
                port = blindPort,
                version = "1.0",
                codecs = listOf("pcm"),
                sampleRate = 48000,
                channels = 2,
                platform = platform
            )
        }

        if (skipDiscovery) return directConnect()

        fun findMatch(): Server? {
            val servers = discoveryManager.servers.value
            if (!name.isNullOrEmpty()) {
                servers.find { it.name.equals(name, ignoreCase = true) && it.platform == platform }?.let { return it }
            }
            return servers.find { it.host == host && it.platform == platform }
        }

        findMatch()?.let { return it }

        // Give discovery (already running from onStart()) a short window to find it.
        val deadline = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < deadline) {
            delay(400)
            findMatch()?.let { return it }
        }

        return directConnect()
    }

    private fun platformForDeepLinkType(type: String?): String = when (type?.lowercase()) {
        "airplay" -> "AirPlay"
        "airplay2" -> "AirPlay2"
        "dlna" -> "DLNA"
        "googlecast", "google_cast", "google-cast" -> "Google Cast"
        else -> "AriaCast"
    }

    private fun defaultPortForDeepLinkPlatform(platform: String): Int = when (platform) {
        "AriaCast" -> 12889
        "AirPlay" -> 5000
        "AirPlay2" -> 7000
        "Google Cast" -> 8009 // CastV2 (TLS)
        else -> 0 // DLNA needs SSDP-sourced control URLs - can't be reached blind.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        sharedPreferences = getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        currentAccentColor = sharedPreferences.getInt(SettingsActivity.KEY_ACCENT_COLOR, R.color.accent_blue)
        currentThemeMode = sharedPreferences.getInt(SettingsActivity.KEY_THEME, ThemeUtils.MODE_NIGHT_FOLLOW_SYSTEM)
        setTheme(ThemeUtils.getThemeForAccent(currentAccentColor))
        
        val pluginPrefs = getSharedPreferences("plugins_prefs", Context.MODE_PRIVATE)
        lastPluginsUpdateTime = pluginPrefs.getLong("plugins_updated_at", 0)
        
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        discoveryManager = DiscoveryManager(this)
        pluginManager = PluginManager(this)
        updateManager = UpdateManager(this)

        stateTextView = findViewById(R.id.stateTextView)
        castButton = findViewById(R.id.castButton)
        discoveryButton = findViewById(R.id.discoveryButton)
        serverRecyclerView = findViewById(R.id.serverRecyclerView)
        permissionButton = findViewById(R.id.permissionButton)
        statusCard = findViewById(R.id.statusCard)
        pluginContainer = findViewById(R.id.pluginContainer)
        groupsSection = findViewById(R.id.groupsSection)
        groupRecyclerView = findViewById(R.id.groupRecyclerView)
        addGroupButton = findViewById(R.id.addGroupButton)
        syncSection = findViewById(R.id.syncSection)
        syncSliderContainer = findViewById(R.id.syncSliderContainer)

        serverListAdapter = ServerAdapter(
            onServerClick = { server ->
                statusCard.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                isUserSelecting = true
                castToServers(listOf(server))
            },
            onDeleteClick = { server ->
                statusCard.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                discoveryManager.removeServer(server.name)
            }
        )

        serverRecyclerView.apply {
            adapter = serverListAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
        }

        groupListAdapter = GroupAdapter(
            onGroupClick = { group ->
                val servers = discoveryManager.servers.value.filter { group.hosts.contains(it.host) }
                if (servers.size == group.hosts.size) {
                    castToServers(servers)
                } else {
                    Toast.makeText(this, getString(R.string.offline_devices_error), Toast.LENGTH_SHORT).show()
                }
            },
            onDeleteClick = { group ->
                deleteGroup(group)
            }
        )

        groupRecyclerView.apply {
            adapter = groupListAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
        }

        castButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (audioCastService?.state?.value == CastState.CASTING) {
                val serviceIntent = Intent(this, AudioCastService::class.java).apply {
                    action = AudioCastService.ACTION_STOP
                }
                startService(serviceIntent)
            } else {
                if (selectedServers.isNotEmpty()) {
                    beginCast()
                } else {
                    Toast.makeText(this, getString(R.string.select_server_first), Toast.LENGTH_SHORT).show()
                }
            }
        }

        discoveryButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            isUserSelecting = false
            discoveryManager.startDiscovery()
            serverRecyclerView.scheduleLayoutAnimation()
        }
        
        addGroupButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            showCreateGroupDialog()
        }

        permissionButton.setOnClickListener {
            showNotificationAccessExplanationDialog()
        }

        lifecycleScope.launch {
            combine(discoveryManager.servers, _audioCastServiceFlow, _refreshTrigger) { servers, service, _ ->
                Pair(servers, service)
            }.collectLatest { (servers, service) ->
                // AriaCompanion (the ESP32 bridge) only speaks AriaCast's own
                // native protocol directly to a receiver — it can't reach
                // AirPlay/DLNA/Google Cast destinations — so while it's the
                // active audio source, only show receivers it can actually use.
                val companionEnabled = sharedPreferences.getBoolean(AriaCompanionActivity.KEY_COMPANION_ENABLED, false)
                val displayedServers = if (companionEnabled) {
                    servers.filter { it.platform == "AriaCast" }
                } else {
                    servers
                }
                serverListAdapter.submitList(displayedServers)

                val isMultiroomEnabled = sharedPreferences.getBoolean(SettingsActivity.KEY_MULTIROOM_ENABLED, false)
                if (isMultiroomEnabled) {
                    val groups = getSavedGroups()
                    val activeGroups = groups.filter { group ->
                        group.hosts.all { host -> servers.any { it.host == host } }
                    }
                    groupsSection.visibility = View.VISIBLE
                    groupListAdapter.submitList(activeGroups)
                } else {
                    groupsSection.visibility = View.GONE
                }

                val lastHost = sharedPreferences.getString(AudioCastService.KEY_LAST_SERVER_HOST, null)
                
                if (isUserSelecting && selectedServers.size == 1) {
                    val sel = selectedServers[0]
                    val found = displayedServers.find { it.host == sel.host && it.platform == sel.platform }
                        ?: displayedServers.find { it.host == sel.host }
                    if (found != null) {
                        selectedServers = listOf(found)
                        serverListAdapter.setSelectedItem(displayedServers.indexOf(found))
                    }
                } else if (lastHost != null && selectedServers.isEmpty()) {
                    val lastPlatform = sharedPreferences.getString(AudioCastService.KEY_LAST_SERVER_PLATFORM, null)
                    val lastServer = displayedServers.find { it.host == lastHost && (lastPlatform == null || it.platform == lastPlatform) }
                        ?: displayedServers.find { it.host == lastHost }
                    if (lastServer != null) {
                        selectedServers = listOf(lastServer)
                        serverListAdapter.setSelectedItem(displayedServers.indexOf(lastServer))
                    }
                }
                
                updateSyncUi()
            }
        }

        lifecycleScope.launch {
            discoveryManager.state.collectLatest {
                discoveryButton.text = when (it) {
                    DiscoveryState.SCANNING -> getString(R.string.scanning)
                    else -> getString(R.string.refresh)
                }
            }
        }
        
        lifecycleScope.launch {
            audioCastServiceFlow.collectLatest { s ->
                s?.pairingPinRequest?.collectLatest { request ->
                    if (request != null) {
                        showPairingPinDialog(request)
                    }
                }
            }
        }
        
        checkNotificationListenerPermission()
        pluginManager.runEnabledPlugins(this, audioCastService)
        lifecycleScope.launch {
            updateManager.checkForUpdates(manual = false)
        }

        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun updateSyncUi() {
        val s = audioCastService
        val isMultiroomEnabled = sharedPreferences.getBoolean(SettingsActivity.KEY_MULTIROOM_ENABLED, false)
        
        if (isMultiroomEnabled && s != null && s.state.value == CastState.CASTING) {
            val destinations = s.activeDestinations.value
            if (destinations.size > 1) {
                syncSection.visibility = View.VISIBLE
                
                val currentHosts = destinations.map { it.host }
                val existingSliders = mutableMapOf<String, View>()
                for (i in 0 until syncSliderContainer.childCount) {
                    val child = syncSliderContainer.getChildAt(i)
                    val host = child.tag as? String
                    if (host != null) {
                        if (host in currentHosts) {
                            existingSliders[host] = child
                        } else {
                            syncSliderContainer.removeView(child)
                        }
                    }
                }

                destinations.forEach { dest ->
                    var sliderItem = existingSliders[dest.host]
                    if (sliderItem == null) {
                        sliderItem = layoutInflater.inflate(R.layout.item_plugin_slider, syncSliderContainer, false)
                        sliderItem.tag = dest.host
                        syncSliderContainer.addView(sliderItem)
                    }

                    val label = sliderItem.findViewById<TextView>(R.id.label)
                    val slider = sliderItem.findViewById<Slider>(R.id.slider)
                    
                    label.text = "${dest.name} (${dest.delayMs}ms)"
                    
                    if (!activeTouchHosts.contains(dest.host)) {
                        slider.apply {
                            valueFrom = 0f
                            valueTo = 2000f
                            stepSize = 20f
                            value = dest.delayMs.toFloat().coerceIn(0f, 2000f)
                            
                            clearOnChangeListeners()
                            addOnChangeListener { _, value, fromUser ->
                                if (fromUser) {
                                    s.setDelay(dest.host, value.toInt())
                                    label.text = "${dest.name} (${value.toInt()}ms)"
                                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                            }
                            
                            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                                override fun onStartTrackingTouch(slider: Slider) {
                                    activeTouchHosts.add(dest.host)
                                    slider.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                }
                                override fun onStopTrackingTouch(slider: Slider) {
                                    activeTouchHosts.remove(dest.host)
                                    s.setDelay(dest.host, slider.value.toInt())
                                    slider.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                }
                            })
                        }
                    }
                }
            } else {
                syncSection.visibility = View.GONE
            }
        } else {
            syncSection.visibility = View.GONE
        }
    }

    private fun getSavedGroups(): List<CastGroup> {
        val json = sharedPreferences.getString("saved_groups", "[]") ?: "[]"
        val array = JSONArray(json)
        val groups = mutableListOf<CastGroup>()
        for (i in 0 until array.length()) {
            groups.add(CastGroup.fromJson(array.getString(i)))
        }
        return groups
    }

    private fun saveGroups(groups: List<CastGroup>) {
        val array = JSONArray()
        groups.forEach { array.put(it.toJson()) }
        sharedPreferences.edit().putString("saved_groups", array.toString()).apply()
        _refreshTrigger.value++
    }

    private fun deleteGroup(group: CastGroup) {
        val groups = getSavedGroups().toMutableList()
        groups.removeAll { it.name == group.name }
        saveGroups(groups)
    }

    private fun showCreateGroupDialog() {
        val servers = discoveryManager.servers.value
        if (servers.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_servers_found), Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_create_group, null)
        val nameInput = dialogView.findViewById<EditText>(R.id.groupNameInput)
        val serverListLayout = dialogView.findViewById<LinearLayout>(R.id.serverSelectionList)
        val selectedHosts = mutableSetOf<String>()

        servers.forEach { server ->
            val checkBox = CheckBox(this).apply {
                text = server.name
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedHosts.add(server.host) else selectedHosts.remove(server.host)
                }
            }
            serverListLayout.addView(checkBox)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.create_speaker_group)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = nameInput.text.toString()
                if (name.isNotEmpty() && selectedHosts.isNotEmpty()) {
                    val groups = getSavedGroups().toMutableList()
                    groups.add(CastGroup(name, selectedHosts.toList()))
                    saveGroups(groups)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        statusCard.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, AudioCastService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
        discoveryManager.startDiscovery()
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            // unbindService() does NOT trigger onServiceDisconnected, so that callback
            // can't be relied on alone to cancel serviceStateJob here.
            unbindService(connection)
            isBound = false
            serviceStateJob?.cancel()
            serviceStateJob = null
        }
        discoveryManager.stopDiscovery()
    }

    override fun onDestroy() {
        super.onDestroy()
        pluginManager.shutdown()
    }
    
    override fun onResume() {
        super.onResume()
        checkNotificationListenerPermission()
        
        val newAccent = sharedPreferences.getInt(SettingsActivity.KEY_ACCENT_COLOR, R.color.accent_blue)
        val newThemeMode = sharedPreferences.getInt(SettingsActivity.KEY_THEME, ThemeUtils.MODE_NIGHT_FOLLOW_SYSTEM)
        
        val pluginPrefs = getSharedPreferences("plugins_prefs", Context.MODE_PRIVATE)
        val currentPluginsUpdate = pluginPrefs.getLong("plugins_updated_at", 0)

        if (newAccent != currentAccentColor || newThemeMode != currentThemeMode || currentPluginsUpdate != lastPluginsUpdateTime) {
            pluginManager.shutdown()
            recreate()
            return
        }

        pluginManager.runEnabledPlugins(this, audioCastService)
        
        val isMultiroomEnabled = sharedPreferences.getBoolean(SettingsActivity.KEY_MULTIROOM_ENABLED, false)
        groupsSection.visibility = if (isMultiroomEnabled) View.VISIBLE else View.GONE
        _refreshTrigger.value++
        updateSyncUi()
    }

    private fun checkNotificationListenerPermission() {
        if (!MediaNotificationListener.isEnabled(this)) {
            permissionButton.visibility = View.VISIBLE
        } else {
            permissionButton.visibility = View.GONE
        }
    }

    private fun showPairingPinDialog(request: AudioCastService.PairingRequest) {
        val isPassword = request.kind == com.aria.ariacast.airplay.AirPlayAuthRequiredException.Kind.PASSWORD
        val input = EditText(this).apply {
            if (isPassword) {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(android.text.InputFilter.LengthFilter(8))
                hint = "0000"
            }
        }
        
        val container = android.widget.FrameLayout(this).apply {
            val params = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(64, 32, 64, 32)
            layoutParams = params
            addView(input)
        }

        val label = if (request.name.isNotEmpty()) request.name else request.host
        MaterialAlertDialogBuilder(this)
            .setTitle(if (isPassword) R.string.airplay_password_title else R.string.airplay_pin_title)
            .setMessage(getString(if (isPassword) R.string.airplay_password_message else R.string.airplay_pin_message, label))
            .setView(container)
            .setCancelable(false)
            .setPositiveButton(R.string.save) { _, _ ->
                val pin = input.text.toString()
                if (pin.isNotEmpty()) {
                    audioCastService?.submitPairingPin(request.host, pin)
                } else {
                    audioCastService?.resetPairingPinRequest()
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                // Reset PIN request if canceled so it can be triggered again
                audioCastService?.resetPairingPinRequest()
            }
            .show()
    }

    private fun updateUi(state: CastState) {
        val oldStateText = stateTextView.text.toString()
        if (oldStateText != state.name) {
            stateTextView.animate().alpha(0f).setDuration(150).withEndAction {
                stateTextView.text = state.name
                stateTextView.animate().alpha(1f).setDuration(150).start()
            }.start()
        }
        
        castButton.isEnabled = (state == CastState.OFF && selectedServers.isNotEmpty()) || state == CastState.CASTING
        
        val accentColor = sharedPreferences.getInt(SettingsActivity.KEY_ACCENT_COLOR, R.color.accent_blue)
        val activeColor = ContextCompat.getColor(this, accentColor)
        val idleColor = ContextCompat.getColor(this, R.color.light_grey)
        val surfaceColor = ContextCompat.getColor(this, R.color.surface_card)

        if (state == CastState.CASTING) {
            castButton.text = getString(R.string.stop)
            castButton.setIconResource(android.R.drawable.ic_media_pause)
            animateCardColors(idleColor, activeColor, surfaceColor, ColorUtils.setAlphaComponent(activeColor, 40))
        } else {
            castButton.text = getString(R.string.start)
            castButton.setIconResource(android.R.drawable.ic_media_play)
            animateCardColors(activeColor, idleColor, ColorUtils.setAlphaComponent(activeColor, 40), surfaceColor)
        }
    }

    private fun animateCardColors(fromStroke: Int, toStroke: Int, fromBg: Int, toBg: Int) {
        currentCardAnimator?.cancel()
        
        currentCardAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 400
            val argbEvaluator = ArgbEvaluator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                val strokeColor = argbEvaluator.evaluate(fraction, fromStroke, toStroke) as Int
                val bgColor = argbEvaluator.evaluate(fraction, fromBg, toBg) as Int
                
                statusCard.setStrokeColor(ColorStateList.valueOf(strokeColor))
                statusCard.setCardBackgroundColor(ColorStateList.valueOf(bgColor))
            }
            start()
        }
    }
}

class GroupAdapter(
    private val onGroupClick: (CastGroup) -> Unit,
    private val onDeleteClick: (CastGroup) -> Unit
) : RecyclerView.Adapter<GroupAdapter.ViewHolder>() {

    private var groups = emptyList<CastGroup>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val group = groups[position]
        holder.groupName.text = group.name
        holder.groupMembers.text = holder.itemView.context.getString(R.string.devices_count_format, group.hosts.size)
        
        holder.itemView.setOnClickListener { 
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onGroupClick(group) 
        }
        holder.deleteButton.setOnClickListener { 
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onDeleteClick(group) 
        }
    }

    override fun getItemCount() = groups.size

    fun submitList(newGroups: List<CastGroup>) {
        groups = newGroups
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val groupName: TextView = view.findViewById(R.id.groupName)
        val groupMembers: TextView = view.findViewById(R.id.groupMembers)
        val deleteButton: ImageButton = view.findViewById(R.id.deleteGroupButton)
    }
}

class ServerAdapter(
    private val onServerClick: (Server) -> Unit,
    private val onDeleteClick: (Server) -> Unit
) : RecyclerView.Adapter<ServerAdapter.ViewHolder>() {

    private var servers = emptyList<Server>()
    private var selectedItem = -1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_server, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val server = servers[position]
        holder.serverName.text = server.name
        holder.serverHost.text = if (server.platform != null) "${server.host} • ${server.platform}" else server.host
        
        val context = holder.itemView.context
        val sharedPrefs = context.getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        val accentColor = sharedPrefs.getInt(SettingsActivity.KEY_ACCENT_COLOR, R.color.accent_blue)
        val colorRes = ContextCompat.getColor(context, accentColor)

        if (selectedItem == position) {
            holder.cardView.setStrokeWidth(4)
            holder.cardView.setStrokeColor(ColorStateList.valueOf(colorRes))
            holder.icon.imageTintList = ColorStateList.valueOf(colorRes)
            holder.cardView.scaleX = 1.02f
            holder.cardView.scaleY = 1.02f
        } else {
            holder.cardView.setStrokeWidth(0)
            holder.icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.light_grey))
            holder.cardView.scaleX = 1.0f
            holder.cardView.scaleY = 1.0f
        }

        if (server.platform == "Manual") {
            holder.moreButton.setImageResource(android.R.drawable.ic_menu_delete)
            holder.moreButton.visibility = View.VISIBLE
            holder.moreButton.setOnClickListener { 
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onDeleteClick(server) 
            }
        } else {
            holder.moreButton.setImageResource(android.R.drawable.ic_menu_more)
            holder.moreButton.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { 
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onServerClick(server)
            setSelectedItem(position)
        }
    }

    override fun getItemCount(): Int = servers.size

    fun submitList(newServers: List<Server>) {
        servers = newServers
        notifyDataSetChanged()
    }

    fun setSelectedItem(position: Int) {
        val previousItem = selectedItem
        selectedItem = position
        if (previousItem != -1) {
            notifyItemChanged(previousItem)
        }
        notifyItemChanged(selectedItem)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardView: MaterialCardView = view as MaterialCardView
        val serverName: TextView = view.findViewById(R.id.serverName)
        val serverHost: TextView = view.findViewById(R.id.serverHost)
        val icon: ImageView = view.findViewById(R.id.serverIcon)
        val moreButton: ImageView = view.findViewById(R.id.moreButton)
    }
}
