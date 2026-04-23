package com.aria.ariacast

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private var packetLogClickCount = 0
    private var lastClickTime: Long = 0
    private lateinit var themeStatusText: TextView
    private lateinit var accentStatusText: TextView
    private lateinit var languageStatusText: TextView
    private lateinit var accentColorPreview: ImageView
    private lateinit var videoCastSwitch: MaterialSwitch
    private lateinit var multiroomSwitch: MaterialSwitch
    private lateinit var updateManager: UpdateManager

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
        languageStatusText = findViewById(R.id.languageStatusText)
        accentColorPreview = findViewById(R.id.accentColorPreview)
        videoCastSwitch = findViewById(R.id.videoCastSwitch)
        multiroomSwitch = findViewById(R.id.multiroomSwitch)
        updateManager = UpdateManager(this)

        findViewById<View>(R.id.themeLayout).setOnClickListener {
            showThemeSelectionDialog()
        }

        findViewById<View>(R.id.accentLayout).setOnClickListener {
            showAccentSelectionDialog()
        }

        findViewById<View>(R.id.languageLayout).setOnClickListener {
            showLanguageSelectionDialog()
        }

        findViewById<View>(R.id.pluginsLayout).setOnClickListener {
            startActivity(Intent(this, PluginsActivity::class.java))
        }

        findViewById<View>(R.id.notificationAccessLayout).setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }

        findViewById<View>(R.id.updateLayout).setOnClickListener {
            lifecycleScope.launch {
                updateManager.checkForUpdates(manual = true)
            }
        }

        findViewById<View>(R.id.githubLayout).setOnClickListener {
            openGitHub()
        }

        findViewById<View>(R.id.resetServerLayout).setOnClickListener {
            resetLastServer()
        }

        findViewById<View>(R.id.resetServerLayout).setOnLongClickListener {
            handlePacketLogClick()
            true
        }

        videoCastSwitch.isChecked = sharedPreferences.getBoolean(KEY_VIDEO_ENABLED, false)
        videoCastSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean(KEY_VIDEO_ENABLED, isChecked).apply()
        }

        multiroomSwitch.isChecked = sharedPreferences.getBoolean(KEY_MULTIROOM_ENABLED, false)
        multiroomSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean(KEY_MULTIROOM_ENABLED, isChecked).apply()
        }

        val versionText = findViewById<TextView>(R.id.versionText)
        versionText.text = getString(R.string.version_format, getString(R.string.app_version))

        updateThemeStatusText()
        updateAccentStatus()
        updateLanguageStatusText()
    }

    private fun openGitHub() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))
        startActivity(intent)
    }

    private fun showThemeSelectionDialog() {
        val themes = arrayOf(
            getString(R.string.theme_light),
            getString(R.string.theme_dark),
            getString(R.string.theme_follow_system)
        )
        val sharedPreferences = getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        val currentTheme = sharedPreferences.getInt(KEY_THEME, ThemeUtils.MODE_NIGHT_FOLLOW_SYSTEM)

        val checkedItem = when(currentTheme) {
            ThemeUtils.MODE_NIGHT_NO -> 0
            ThemeUtils.MODE_NIGHT_YES -> 1
            else -> 2
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.select_appearance))
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
        val accents = arrayOf(
            getString(R.string.accent_blue),
            getString(R.string.accent_purple),
            getString(R.string.accent_green),
            getString(R.string.accent_orange),
            getString(R.string.accent_pink)
        )
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
            .setTitle(getString(R.string.select_accent_color))
            .setSingleChoiceItems(accents, checkedItem) { dialog, which ->
                val selectedColor = colors[which]
                sharedPreferences.edit().putInt(KEY_ACCENT_COLOR, selectedColor).apply()

                updateAccentStatus()
                dialog.dismiss()
                recreate()
            }
            .show()
    }

    private fun showLanguageSelectionDialog() {
        val languages = arrayOf(
            getString(R.string.language_default),
            "English",
            "Deutsch",
            "Español",
            "Français",
            "Italiano",
            "Nederlands",
            "日本語",
            "简体中文"
        )
        val codes = arrayOf("", "en", "de", "es", "fr", "it", "nl", "ja", "zh")

        val currentLocaleCode = AppCompatDelegate.getApplicationLocales().get(0)?.language ?: ""
        val checkedItem = codes.indexOf(currentLocaleCode).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.select_language))
            .setSingleChoiceItems(languages, checkedItem) { dialog, which ->
                val selectedCode = codes[which]
                val appLocale: LocaleListCompat = if (selectedCode.isEmpty()) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(selectedCode)
                }
                AppCompatDelegate.setApplicationLocales(appLocale)
                dialog.dismiss()
            }
            .show()
    }

    private fun updateThemeStatusText() {
        val sharedPreferences = getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        val currentTheme = sharedPreferences.getInt(KEY_THEME, ThemeUtils.MODE_NIGHT_FOLLOW_SYSTEM)
        themeStatusText.text = when(currentTheme) {
            ThemeUtils.MODE_NIGHT_NO -> getString(R.string.theme_light)
            ThemeUtils.MODE_NIGHT_YES -> getString(R.string.theme_dark)
            else -> getString(R.string.theme_follow_system)
        }
    }

    private fun updateAccentStatus() {
        val sharedPreferences = getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        val currentAccent = sharedPreferences.getInt(KEY_ACCENT_COLOR, R.color.accent_blue)

        val accentName = when(currentAccent) {
            R.color.accent_purple -> getString(R.string.accent_purple)
            R.color.accent_green -> getString(R.string.accent_green)
            R.color.accent_orange -> getString(R.string.accent_orange)
            R.color.accent_pink -> getString(R.string.accent_pink)
            else -> getString(R.string.accent_blue)
        }

        accentStatusText.text = accentName
        accentColorPreview.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, currentAccent))
    }

    private fun updateLanguageStatusText() {
        val currentLocaleCode = AppCompatDelegate.getApplicationLocales().get(0)?.language ?: ""
        languageStatusText.text = when(currentLocaleCode) {
            "en" -> "English"
            "de" -> "Deutsch"
            "es" -> "Español"
            "fr" -> "Français"
            "it" -> "Italiano"
            "nl" -> "Nederlands"
            "ja" -> "日本語"
            "zh" -> "简体中文"
            else -> getString(R.string.language_default)
        }
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
            val message = if (remaining == 1) {
                getString(R.string.secret_logs_one_tap)
            } else {
                getString(R.string.secret_logs_multiple_taps, remaining)
            }
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
        Toast.makeText(this, getString(R.string.server_cleared), Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val KEY_THEME = "prefs_theme"
        const val KEY_ACCENT_COLOR = "prefs_accent_color"
        const val KEY_VIDEO_ENABLED = "prefs_video_enabled"
        const val KEY_MULTIROOM_ENABLED = "prefs_multiroom_enabled"
        const val GITHUB_URL = "https://github.com/AriaCast/AriaCast-app"
    }
}
