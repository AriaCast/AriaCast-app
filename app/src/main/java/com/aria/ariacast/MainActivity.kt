package com.aria.ariacast

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var audioCastService: AudioCastService? = null
    private var isBound = false
    private var selectedServer: Server? = null
    private var isUserSelecting = false

    private lateinit var stateTextView: TextView
    private lateinit var castButton: MaterialButton
    private lateinit var discoveryButton: MaterialButton
    private lateinit var serverRecyclerView: RecyclerView
    private lateinit var permissionButton: MaterialButton
    private lateinit var statusCard: MaterialCardView
    lateinit var pluginContainer: LinearLayout

    private lateinit var discoveryManager: DiscoveryManager
    private lateinit var serverListAdapter: ServerAdapter
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var pluginManager: PluginManager

    private val _audioCastServiceFlow = MutableStateFlow<AudioCastService?>(null)
    val audioCastServiceFlow = _audioCastServiceFlow.asStateFlow()

    private var currentCardAnimator: ValueAnimator? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as AudioCastService.AudioCastBinder
            val s = binder.getService()
            audioCastService = s
            _audioCastServiceFlow.value = s
            isBound = true
            lifecycleScope.launch {
                s.state.collectLatest { state ->
                    updateUi(state)
                }
            }
            
            // Re-run plugins when service connects to ensure they pick up the active server immediately
            pluginManager.runEnabledPlugins(this@MainActivity, s)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            audioCastService = null
            _audioCastServiceFlow.value = null
            isBound = false
        }
    }

    private val startMediaProjection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK && it.data != null) {
            selectedServer?.let { server ->
                val serviceIntent = Intent(this, AudioCastService::class.java).apply {
                    action = AudioCastService.ACTION_START
                    putExtra(AudioCastService.EXTRA_MEDIA_PROJECTION_TOKEN, it.data)
                    putExtra(AudioCastService.EXTRA_SERVER_HOST, server.host)
                    putExtra(AudioCastService.EXTRA_SERVER_PORT, server.port)
                    putExtra(AudioCastService.EXTRA_SERVER_NAME, server.name)
                    putExtra(AudioCastService.EXTRA_SERVER_PLATFORM, server.platform)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
            }
        } else {
            Toast.makeText(this, "MediaProjection permission denied.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        sharedPreferences = getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        val accentColor = sharedPreferences.getInt(SettingsActivity.KEY_ACCENT_COLOR, R.color.accent_blue)
        setTheme(ThemeUtils.getThemeForAccent(accentColor))
        
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        discoveryManager = DiscoveryManager(this)
        pluginManager = PluginManager(this)

        stateTextView = findViewById(R.id.stateTextView)
        castButton = findViewById(R.id.castButton)
        discoveryButton = findViewById(R.id.discoveryButton)
        serverRecyclerView = findViewById(R.id.serverRecyclerView)
        permissionButton = findViewById(R.id.permissionButton)
        statusCard = findViewById(R.id.statusCard)
        pluginContainer = findViewById(R.id.pluginContainer)

        serverListAdapter = ServerAdapter { server ->
            isUserSelecting = true
            selectedServer = server
            if (server.name.contains("MusicAssistant", ignoreCase = true)) {
                discoveryManager.startDiscovery()
            }
            startMediaProjection.launch(mediaProjectionManager.createScreenCaptureIntent())
        }

        serverRecyclerView.apply {
            adapter = serverListAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
        }

        castButton.setOnClickListener {
            it.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).withEndAction {
                it.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
            }.start()

            if (audioCastService?.state?.value == CastState.CASTING) {
                val serviceIntent = Intent(this, AudioCastService::class.java).apply {
                    action = AudioCastService.ACTION_STOP
                }
                startService(serviceIntent)
            } else {
                if (selectedServer != null) {
                    startMediaProjection.launch(mediaProjectionManager.createScreenCaptureIntent())
                } else {
                    Toast.makeText(this, "Please select a server first.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        discoveryButton.setOnClickListener {
            isUserSelecting = false
            discoveryManager.startDiscovery()
            serverRecyclerView.scheduleLayoutAnimation()
        }
        
        permissionButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }

        lifecycleScope.launch {
            discoveryManager.servers.collectLatest { servers ->
                serverListAdapter.submitList(servers)

                val lastHost = sharedPreferences.getString(AudioCastService.KEY_LAST_SERVER_HOST, null)
                
                if (isUserSelecting && selectedServer != null) {
                    val currentHost = selectedServer?.host
                    val found = servers.find { it.host == currentHost }
                    if (found != null) {
                        selectedServer = found
                        serverListAdapter.setSelectedItem(servers.indexOf(found))
                    }
                } else if (lastHost != null) {
                    val lastServer = servers.find { it.host == lastHost }
                    if (lastServer != null) {
                        selectedServer = lastServer
                        val index = servers.indexOf(lastServer)
                        serverListAdapter.setSelectedItem(index)
                    }
                }
            }
        }

        lifecycleScope.launch {
            discoveryManager.state.collectLatest {
                discoveryButton.text = when (it) {
                    DiscoveryState.SCANNING -> "Scanning..."
                    else -> "Refresh"
                }
            }
        }
        
        checkNotificationListenerPermission()

        // Run enabled plugins
        pluginManager.runEnabledPlugins(this, audioCastService)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
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
            unbindService(connection)
            isBound = false
        }
        discoveryManager.stopDiscovery()
    }
    
    override fun onResume() {
        super.onResume()
        checkNotificationListenerPermission()
    }

    private fun checkNotificationListenerPermission() {
        if (!MediaNotificationListener.isEnabled(this)) {
            permissionButton.visibility = View.VISIBLE
        } else {
            permissionButton.visibility = View.GONE
        }
    }

    private fun updateUi(state: CastState) {
        val oldStateText = stateTextView.text.toString()
        if (oldStateText != state.name) {
            stateTextView.animate().alpha(0f).setDuration(150).withEndAction {
                stateTextView.text = state.name
                stateTextView.animate().alpha(1f).setDuration(150).start()
            }.start()
        }
        
        castButton.isEnabled = state == CastState.OFF && selectedServer != null || state == CastState.CASTING
        
        val accentColor = sharedPreferences.getInt(SettingsActivity.KEY_ACCENT_COLOR, R.color.accent_blue)
        val activeColor = ContextCompat.getColor(this, accentColor)
        val idleColor = ContextCompat.getColor(this, R.color.light_grey)
        val surfaceColor = ContextCompat.getColor(this, R.color.surface)

        if (state == CastState.CASTING) {
            castButton.text = "Stop"
            castButton.setIconResource(android.R.drawable.ic_media_pause)
            animateCardColors(idleColor, activeColor, surfaceColor, ColorUtils.setAlphaComponent(activeColor, 40))
        } else {
            castButton.text = "Start"
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

class ServerAdapter(private val onServerClick: (Server) -> Unit) : RecyclerView.Adapter<ServerAdapter.ViewHolder>() {

    private var servers = emptyList<Server>()
    private var selectedItem = -1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_server, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val server = servers[position]
        holder.serverName.text = server.name
        holder.serverHost.text = server.host
        
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

        holder.itemView.setOnClickListener { 
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
    }
}
