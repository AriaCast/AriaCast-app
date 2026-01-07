package com.aria.ariacast

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.divider.MaterialDividerItemDecoration
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var audioCastService: AudioCastService? = null
    private var isBound = false
    private var selectedServer: Server? = null

    private lateinit var stateTextView: TextView
    private lateinit var castButton: Button
    private lateinit var discoveryButton: Button
    private lateinit var serverRecyclerView: RecyclerView
    private lateinit var volumeControlLayout: LinearLayout
    private lateinit var volumeUpButton: Button
    private lateinit var volumeDownButton: Button
    private lateinit var permissionButton: Button

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
                }
                ContextCompat.startForegroundService(this, serviceIntent)
            }
        } else {
            Toast.makeText(this, "MediaProjection permission denied.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        discoveryManager = DiscoveryManager(this)
        sharedPreferences = getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)

        stateTextView = findViewById(R.id.stateTextView)
        castButton = findViewById(R.id.castButton)
        discoveryButton = findViewById(R.id.discoveryButton)
        serverRecyclerView = findViewById(R.id.serverRecyclerView)
        volumeControlLayout = findViewById(R.id.volumeControlLayout)
        volumeUpButton = findViewById(R.id.volumeUpButton)
        volumeDownButton = findViewById(R.id.volumeDownButton)
        permissionButton = findViewById(R.id.permissionButton)

        serverListAdapter = ServerAdapter { server ->
            selectedServer = server
            startMediaProjection.launch(mediaProjectionManager.createScreenCaptureIntent())
        }

        serverRecyclerView.apply {
            adapter = serverListAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
            addItemDecoration(MaterialDividerItemDecoration(this@MainActivity, LinearLayoutManager.VERTICAL))
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

        volumeUpButton.setOnClickListener {
            audioCastService?.sendVolumeCommand("up")
        }

        volumeDownButton.setOnClickListener {
            audioCastService?.sendVolumeCommand("down")
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
                        // Highlight the last server in the list
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
                    else -> "Discover Servers"
                }
            }
        }
        
        checkNotificationListenerPermission()
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
        stateTextView.text = "State: $state"
        castButton.isEnabled = state == CastState.OFF && selectedServer != null || state == CastState.CASTING
        if (state == CastState.CASTING) {
            castButton.text = "Stop Casting"
            castButton.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.red_500))
            volumeControlLayout.visibility = View.VISIBLE
        } else {
            castButton.text = "Start Casting"
            castButton.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            volumeControlLayout.visibility = View.GONE
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
        holder.itemView.isSelected = selectedItem == position
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
        val serverName: TextView = view.findViewById(R.id.serverName)
        val serverHost: TextView = view.findViewById(R.id.serverHost)
    }
}
