package com.aria.ariacast

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : AppCompatActivity() {

    private var packetLogClickCount = 0
    private var lastClickTime: Long = 0
    private lateinit var themeStatusText: TextView
    private lateinit var accentStatusText: TextView
    private lateinit var accentColorPreview: ImageView
    private lateinit var updateStatusText: TextView
    private lateinit var videoCastSwitch: MaterialSwitch

    override fun onCreate(savedInstanceState: Bundle?) {
        val sharedPreferences = getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        val accentColor = sharedPreferences.getInt(KEY_ACCENT_COLOR, R.color.accent_blue)
        setTheme(ThemeUtils.getThemeForAccent(accentColor))
        
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        themeStatusText = findViewById(R.id.themeStatusText)
        accentStatusText = findViewById(R.id.accentStatusText)
        accentColorPreview = findViewById(R.id.accentColorPreview)
        updateStatusText = findViewById(R.id.updateStatusText)
        videoCastSwitch = findViewById(R.id.videoCastSwitch)

        findViewById<MaterialCardView>(R.id.themeCard).setOnClickListener {
            showThemeSelectionDialog()
        }

        findViewById<MaterialCardView>(R.id.accentCard).setOnClickListener {
            showAccentSelectionDialog()
        }

        findViewById<MaterialCardView>(R.id.packetLogCard).setOnClickListener {
            handlePacketLogClick()
        }

        findViewById<MaterialCardView>(R.id.resetServerCard).setOnClickListener {
            resetLastServer()
        }

        findViewById<MaterialCardView>(R.id.notificationAccessCard).setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }

        findViewById<MaterialCardView>(R.id.githubCard).setOnClickListener {
            openGitHub()
        }

        findViewById<MaterialCardView>(R.id.updateCard).setOnClickListener {
            checkForUpdates(manual = true)
        }

        videoCastSwitch.isChecked = sharedPreferences.getBoolean(KEY_VIDEO_ENABLED, false)
        videoCastSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean(KEY_VIDEO_ENABLED, isChecked).apply()
            val status = if (isChecked) "Video enabled" else "Video disabled"
            Toast.makeText(this, status, Toast.LENGTH_SHORT).show()
        }

        updateThemeStatusText()
        updateAccentStatus()
        
        val version = getString(R.string.app_version)
        updateStatusText.text = "AriaCast v$version"
    }

    private fun openGitHub() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))
        startActivity(intent)
    }

    private fun checkForUpdates(manual: Boolean) {
        val version = getString(R.string.app_version)
        val latestVersion = "1.0.5"
        
        if (latestVersion > version) {
            showUpdateDialog(latestVersion)
            showUpdateNotification(latestVersion)
        } else if (manual) {
            Toast.makeText(this, "You are on the latest version!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showUpdateDialog(latestVersion: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Update Available")
            .setMessage("A new version (v$latestVersion) of AriaCast is available. Would you like to download it from GitHub?")
            .setPositiveButton("Download") { _, _ ->
                openGitHub()
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun showUpdateNotification(latestVersion: String) {
        val channelId = "updates_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "App Updates"
            val descriptionText = "Notifications for new AriaCast versions"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_tile_icon)
            .setContentTitle("Update Available")
            .setContentText("AriaCast v$latestVersion is now available on GitHub.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            with(NotificationManagerCompat.from(this)) {
                notify(UPDATE_NOTIFICATION_ID, builder.build())
            }
        } catch (e: SecurityException) {
        }
    }

    private fun showThemeSelectionDialog() {
        val themes = arrayOf("Light", "Dark", "Follow System")
        val sharedPreferences = getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        val currentTheme = sharedPreferences.getInt(KEY_THEME, ThemeUtils.MODE_NIGHT_FOLLOW_SYSTEM)
        
        val checkedItem = when(currentTheme) {
            ThemeUtils.MODE_NIGHT_NO -> 0
            ThemeUtils.MODE_NIGHT_YES -> 1
            else -> 2
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Select Appearance")
            .setSingleChoiceItems(themes, checkedItem) { dialog, which ->
                val selectedTheme = when(which) {
                    0 -> ThemeUtils.MODE_NIGHT_NO
                    1 -> ThemeUtils.MODE_NIGHT_YES
                    else -> ThemeUtils.MODE_NIGHT_FOLLOW_SYSTEM
                }
                
                sharedPreferences.edit().putInt(KEY_THEME, selectedTheme).apply()
                ThemeUtils.applyTheme(selectedTheme)
                updateThemeStatusText()
                dialog.dismiss()
            }
            .show()
    }

    private fun showAccentSelectionDialog() {
        val accents = arrayOf("Blue", "Purple", "Green", "Orange", "Pink")
        val colors = intArrayOf(
            R.color.accent_blue,
            R.color.accent_purple,
            R.color.accent_green,
            R.color.accent_orange,
            R.color.accent_pink
        )
        
        val sharedPreferences = getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        val currentAccent = sharedPreferences.getInt(KEY_ACCENT_COLOR, R.color.accent_blue)
        
        val checkedItem = colors.indexOf(currentAccent).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle("Select Accent Color")
            .setSingleChoiceItems(accents, checkedItem) { dialog, which ->
                val selectedColor = colors[which]
                sharedPreferences.edit().putInt(KEY_ACCENT_COLOR, selectedColor).apply()
                
                updateAccentStatus()
                dialog.dismiss()
                recreate()
            }
            .show()
    }

    private fun updateThemeStatusText() {
        val sharedPreferences = getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        val currentTheme = sharedPreferences.getInt(KEY_THEME, ThemeUtils.MODE_NIGHT_FOLLOW_SYSTEM)
        themeStatusText.text = when(currentTheme) {
            ThemeUtils.MODE_NIGHT_NO -> "Light"
            ThemeUtils.MODE_NIGHT_YES -> "Dark"
            else -> "Follow System"
        }
    }

    private fun updateAccentStatus() {
        val sharedPreferences = getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        val currentAccent = sharedPreferences.getInt(KEY_ACCENT_COLOR, R.color.accent_blue)
        
        val accentName = when(currentAccent) {
            R.color.accent_purple -> "Purple"
            R.color.accent_green -> "Green"
            R.color.accent_orange -> "Orange"
            R.color.accent_pink -> "Pink"
            else -> "Blue"
        }
        
        accentStatusText.text = accentName
        accentColorPreview.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, currentAccent))
    }

    private fun handlePacketLogClick() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastClickTime > 2000) {
            packetLogClickCount = 1
        } else {
            packetLogClickCount++
        }
        lastClickTime = currentTime

        if (packetLogClickCount >= 3) {
            packetLogClickCount = 0
            startActivity(Intent(this, PacketLogActivity::class.java))
        } else {
            val remaining = 3 - packetLogClickCount
            val message = if (remaining == 1) "One more tap to enter..." else "$remaining more taps to enter..."
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetLastServer() {
        val sharedPreferences = getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        sharedPreferences.edit().apply {
            remove(AudioCastService.KEY_LAST_SERVER_HOST)
            remove(AudioCastService.KEY_LAST_SERVER_PORT)
            remove(AudioCastService.KEY_LAST_SERVER_NAME)
            apply()
        }
        Toast.makeText(this, "Last server connection cleared", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val KEY_THEME = "prefs_theme"
        const val KEY_ACCENT_COLOR = "prefs_accent_color"
        const val KEY_VIDEO_ENABLED = "prefs_video_enabled"
        const val GITHUB_URL = "https://github.com/AirPlr/AriaCast-app"
        const val UPDATE_NOTIFICATION_ID = 1001
    }
}
