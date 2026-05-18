package com.aria.ariacast

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch

class ProtocolsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val sharedPreferences = getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        val accentColor = sharedPreferences.getInt(SettingsActivity.KEY_ACCENT_COLOR, R.color.accent_blue)
        setTheme(ThemeUtils.getThemeForAccent(accentColor))

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_protocols)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val dlnaSwitch = findViewById<MaterialSwitch>(R.id.dlnaSwitch)
        val googleCastSwitch = findViewById<MaterialSwitch>(R.id.googleCastSwitch)
        val airPlaySwitch = findViewById<MaterialSwitch>(R.id.airPlaySwitch)
        val airPlay2Switch = findViewById<MaterialSwitch>(R.id.airPlay2Switch)

        dlnaSwitch.isChecked = sharedPreferences.getBoolean(KEY_DLNA_ENABLED, false)
        dlnaSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean(KEY_DLNA_ENABLED, isChecked).apply()
        }

        googleCastSwitch.isChecked = sharedPreferences.getBoolean(KEY_GOOGLE_CAST_ENABLED, false)
        googleCastSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean(KEY_GOOGLE_CAST_ENABLED, isChecked).apply()
        }

        airPlaySwitch.isChecked = sharedPreferences.getBoolean(KEY_AIRPLAY_ENABLED, false)
        airPlaySwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean(KEY_AIRPLAY_ENABLED, isChecked).apply()
        }

        airPlay2Switch.isChecked = sharedPreferences.getBoolean(KEY_AIRPLAY2_ENABLED, false)
        airPlay2Switch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean(KEY_AIRPLAY2_ENABLED, isChecked).apply()
        }
    }

    companion object {
        const val KEY_DLNA_ENABLED = "dlna_enabled"
        const val KEY_GOOGLE_CAST_ENABLED = "google_cast_enabled"
        const val KEY_AIRPLAY_ENABLED = "airplay_enabled"
        const val KEY_AIRPLAY2_ENABLED = "airplay2_enabled"
    }
}
