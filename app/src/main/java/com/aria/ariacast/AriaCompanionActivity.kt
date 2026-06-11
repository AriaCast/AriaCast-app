package com.aria.ariacast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class AriaCompanionActivity : AppCompatActivity() {

    private val tag = "AriaCompanion"
    private val serviceType = "_ariacompanion._tcp"

    private lateinit var statusIcon: ImageView
    private lateinit var statusTitle: TextView
    private lateinit var statusSubtitle: TextView
    private lateinit var scanButton: MaterialButton
    private lateinit var ssidInput: TextInputEditText
    private lateinit var passInput: TextInputEditText
    private lateinit var configureButton: MaterialButton
    private lateinit var audioSourceSwitch: MaterialSwitch

    private lateinit var nsdManager: NsdManager
    private var isDiscovering = false

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(tag, "Discovery start failed: $errorCode")
            runOnUiThread { setScanningState(false) }
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onDiscoveryStarted(serviceType: String) {
            Log.d(tag, "Discovery started")
        }
        override fun onDiscoveryStopped(serviceType: String) {
            isDiscovering = false
        }
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(tag, "Resolve failed: $errorCode")
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val ip = serviceInfo.host?.hostAddress ?: return
                    val port = serviceInfo.port
                    val name = serviceInfo.serviceName
                    Log.d(tag, "Companion found: $name @ $ip:$port")
                    runOnUiThread { onCompanionFound(name, ip, port) }
                }
            })
        }
        override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        setTheme(ThemeUtils.getThemeForAccent(prefs.getInt(SettingsActivity.KEY_ACCENT_COLOR, R.color.accent_blue)))

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_aria_companion)

        nsdManager = getSystemService(NSD_SERVICE) as NsdManager

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        statusIcon = findViewById(R.id.companionStatusIcon)
        statusTitle = findViewById(R.id.companionStatusTitle)
        statusSubtitle = findViewById(R.id.companionStatusSubtitle)
        scanButton = findViewById(R.id.scanButton)
        ssidInput = findViewById(R.id.ssidInput)
        passInput = findViewById(R.id.passInput)
        configureButton = findViewById(R.id.configureButton)
        audioSourceSwitch = findViewById(R.id.audioSourceSwitch)

        audioSourceSwitch.isChecked = prefs.getBoolean(KEY_COMPANION_ENABLED, false)
        audioSourceSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_COMPANION_ENABLED, checked).apply()
        }

        val savedIp = prefs.getString(KEY_COMPANION_IP, null)
        if (!savedIp.isNullOrEmpty()) {
            val savedPort = prefs.getInt(KEY_COMPANION_PORT, AudioCastService.COMPANION_STREAM_PORT)
            statusTitle.text = getString(R.string.companion_status_found, "AriaCompanion", "$savedIp:$savedPort")
            statusSubtitle.text = savedIp
            statusIcon.setImageResource(android.R.drawable.presence_online)
        }

        scanButton.setOnClickListener { startScan() }

        findViewById<android.view.View>(R.id.manualIpLayout).setOnClickListener { showManualIpDialog() }

        configureButton.setOnClickListener {
            val ssid = ssidInput.text?.toString()?.trim() ?: ""
            val pass = passInput.text?.toString() ?: ""
            if (ssid.isEmpty()) {
                Toast.makeText(this, getString(R.string.companion_wifi_ssid), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            hideKeyboard()
            sendWifiCredentials(ssid, pass)
        }
    }

    private fun startScan() {
        if (isDiscovering) {
            try { nsdManager.stopServiceDiscovery(discoveryListener) } catch (e: Exception) {}
        }
        setScanningState(true)
        statusTitle.text = getString(R.string.companion_status_searching)
        statusSubtitle.text = ""
        statusIcon.setImageResource(android.R.drawable.ic_menu_search)
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            isDiscovering = true
        } catch (e: Exception) {
            Log.e(tag, "Failed to start discovery: ${e.message}")
            setScanningState(false)
        }
    }

    private fun setScanningState(scanning: Boolean) {
        scanButton.isEnabled = !scanning
        scanButton.text = if (scanning) "…" else getString(R.string.companion_scan)
    }

    private fun onCompanionFound(name: String, ip: String, port: Int) {
        setScanningState(false)
        try { nsdManager.stopServiceDiscovery(discoveryListener) } catch (e: Exception) {}
        isDiscovering = false

        val prefs = getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_COMPANION_IP, ip)
            .putInt(KEY_COMPANION_PORT, port)
            .apply()

        statusTitle.text = getString(R.string.companion_status_found, name, "$ip:$port")
        statusSubtitle.text = ip
        statusIcon.setImageResource(android.R.drawable.presence_online)
        Toast.makeText(this, getString(R.string.companion_saved, ip), Toast.LENGTH_SHORT).show()
    }

    private fun showManualIpDialog() {
        val prefs = getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.companion_manual_ip_hint)
            setText(prefs.getString(KEY_COMPANION_IP, ""))
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.companion_manual_ip)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val ip = input.text?.toString()?.trim() ?: ""
                if (ip.isNotEmpty()) {
                    prefs.edit()
                        .putString(KEY_COMPANION_IP, ip)
                        .putInt(KEY_COMPANION_PORT, AudioCastService.COMPANION_STREAM_PORT)
                        .apply()
                    statusTitle.text = getString(R.string.companion_status_found, "AriaCompanion", ip)
                    statusSubtitle.text = ip
                    statusIcon.setImageResource(android.R.drawable.presence_online)
                    Toast.makeText(this, getString(R.string.companion_ip_saved, ip), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun sendWifiCredentials(ssid: String, pass: String) {
        // Android routes traffic via mobile data when WiFi has no internet (ESP32 AP).
        // Explicitly bind the connection to the WiFi network so it reaches 192.168.4.1.
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                    val wifiNetwork = cm.allNetworks.firstOrNull { network ->
                        cm.getNetworkCapabilities(network)
                            ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
                    }
                    val url = URL("http://192.168.4.1/wifi")
                    val body = "ssid=${URLEncoder.encode(ssid, "UTF-8")}&pass=${URLEncoder.encode(pass, "UTF-8")}"
                    val conn = (wifiNetwork?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
                    conn.apply {
                        requestMethod = "POST"
                        doOutput = true
                        connectTimeout = 5000
                        readTimeout = 5000
                        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    }
                    OutputStreamWriter(conn.outputStream).use { it.write(body) }
                    conn.responseCode == 200
                } catch (e: Exception) {
                    Log.e(tag, "Configure failed: ${e.message}")
                    false
                }
            }
            Toast.makeText(
                this@AriaCompanionActivity,
                if (ok) getString(R.string.companion_configure_ok) else getString(R.string.companion_configure_fail),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun hideKeyboard() {
        currentFocus?.let {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(it.windowToken, 0)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isDiscovering) {
            try { nsdManager.stopServiceDiscovery(discoveryListener) } catch (e: Exception) {}
        }
    }

    companion object {
        const val KEY_COMPANION_ENABLED = "companion_enabled"
        const val KEY_COMPANION_IP = "companion_ip"
        const val KEY_COMPANION_PORT = "companion_port"
    }
}
