package com.aria.ariacast

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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var audioCastService: AudioCastService? = null
    private var isBound = false
    private var selectedServer: Server? = null

    private lateinit var stateTextView: TextView
    private lateinit var castButton: MaterialButton
    private lateinit var discoveryButton: MaterialButton
    private lateinit var serverRecyclerView: RecyclerView
    private lateinit var permissionButton: MaterialButton
    private lateinit var statusCard: MaterialCardView

    private lateinit var discoveryManager: DiscoveryManager
    private lateinit var serverListAdapter: ServerAdapter
    private lateinit var sharedPreferences: SharedPreferences

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as AudioCastService.AudioCastBinder
            audioCastService = binder.getService()
            isBound = true
            lifecycleScope.launch {
                audioCastService?.state?.collectLatest { state ->
                    updateUi(state)
                }
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            audioCastService = null
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

        stateTextView = findViewById(R.id.stateTextView)
        castButton = findViewById(R.id.castButton)
        discoveryButton = findViewById(R.id.discoveryButton)
        serverRecyclerView = findViewById(R.id.serverRecyclerView)
        permissionButton = findViewById(R.id.permissionButton)
        statusCard = findViewById(R.id.statusCard)

        serverListAdapter = ServerAdapter { server ->
            selectedServer = server
            startMediaProjection.launch(mediaProjectionManager.createScreenCaptureIntent())
        }

        serverRecyclerView.apply {
            adapter = serverListAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
        }

        castButton.setOnClickListener {
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
            discoveryManager.startDiscovery()
        }
        
        permissionButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }

        lifecycleScope.launch {
            discoveryManager.servers.collectLatest { servers ->
                serverListAdapter.submitList(servers)

                val lastHost = sharedPreferences.getString(AudioCastService.KEY_LAST_SERVER_HOST, null)
                if (lastHost != null) {
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
        
        // Check if theme needs update
        val currentAccent = sharedPreferences.getInt(SettingsActivity.KEY_ACCENT_COLOR, R.color.accent_blue)
        // This is a simple way to "refresh" if settings changed. 
        // For a more robust solution, use a listener or check against a stored value.
    }

    private fun checkNotificationListenerPermission() {
        if (!MediaNotificationListener.isEnabled(this)) {
            permissionButton.visibility = View.VISIBLE
        } else {
            permissionButton.visibility = View.GONE
        }
    }

    private fun updateUi(state: CastState) {
        stateTextView.text = state.name
        castButton.isEnabled = state == CastState.OFF && selectedServer != null || state == CastState.CASTING
        
        val accentColor = sharedPreferences.getInt(SettingsActivity.KEY_ACCENT_COLOR, R.color.accent_blue)
        val colorRes = ContextCompat.getColor(this, accentColor)

        if (state == CastState.CASTING) {
            castButton.text = "Stop"
            castButton.setIconResource(android.R.drawable.ic_media_pause)
            statusCard.setStrokeColor(ColorStateList.valueOf(colorRes))
            // Lighten the background slightly for casting state
            statusCard.setCardBackgroundColor(ColorStateList.valueOf(colorRes).withAlpha(40))
        } else {
            castButton.text = "Start"
            castButton.setIconResource(android.R.drawable.ic_media_play)
            statusCard.setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.light_grey)))
            statusCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.surface))
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
            holder.icon.setImageResource(android.R.drawable.ic_menu_slideshow)
            holder.icon.imageTintList = ColorStateList.valueOf(colorRes)
        } else {
            holder.cardView.setStrokeWidth(0)
            holder.icon.setImageResource(android.R.drawable.ic_menu_slideshow)
            holder.icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.light_grey))
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
