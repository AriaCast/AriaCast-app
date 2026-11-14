package com.aria.ariacast.automation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.aria.ariacast.AudioCastService
import com.aria.ariacast.MainActivity
import com.aria.ariacast.R
import com.aria.ariacast.companion.CompanionHub
import com.aria.ariacast.pipeline.SourceKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * One-tap casting for the home-screen widget, launcher shortcuts and automation apps
 * (Tasker, MacroDroid). Casts to the last speaker used; automation apps may name another
 * speaker only when "Allow automation apps" is on. Android still shows the screen-capture
 * prompt unless AriaCompanion is plugged in.
 */
class CastShortcutActivity : ComponentActivity() {
    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            start(Intent(this, AudioCastService::class.java).setAction(AudioCastService.ACTION_START)
                .putExtra(AudioCastService.EXTRA_MEDIA_PROJECTION_TOKEN, r.data))
        }
        finish()
    }
    private lateinit var target: JSONObject

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        val automation = intent.action == ACTION_CAST && intent.hasExtra(EXTRA_HOST)
        if (automation && !prefs.getBoolean(KEY_AUTOMATION, false)) {
            Toast.makeText(this, getString(R.string.automation_disabled), Toast.LENGTH_LONG).show()
            finish(); return
        }
        val host = if (automation) intent.getStringExtra(EXTRA_HOST) else prefs.getString(AudioCastService.KEY_LAST_SERVER_HOST, null)
        if (host == null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish(); return
        }
        val platform = if (automation) MainActivity.platformForDeepLinkType(intent.getStringExtra(EXTRA_TYPE))
            else prefs.getString(AudioCastService.KEY_LAST_SERVER_PLATFORM, "AriaCast") ?: "AriaCast"
        val port = if (automation) intent.getIntExtra(EXTRA_PORT, MainActivity.defaultPortForDeepLinkPlatform(platform))
            else prefs.getInt(AudioCastService.KEY_LAST_SERVER_PORT, 0)
        val name = (if (automation) intent.getStringExtra(EXTRA_NAME) else prefs.getString(AudioCastService.KEY_LAST_SERVER_NAME, null)) ?: host
        target = JSONObject().put("name", name).put("host", host).put("port", port).put("platform", platform)

        if (CompanionHub.get(this).isUsbAttached) {
            start(Intent(this, AudioCastService::class.java).setAction(AudioCastService.ACTION_START_SOURCE)
                .putExtra(AudioCastService.EXTRA_SOURCE, SourceKind.COMPANION_USB.id))
            finish()
        } else {
            projection.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        }
    }

    private fun start(intent: Intent) {
        intent.putExtra(AudioCastService.EXTRA_SERVERS_JSON, JSONArray().put(target).toString())
        ContextCompat.startForegroundService(this, intent)
    }

    companion object {
        const val ACTION_CAST = "com.aria.ariacast.action.CAST"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_TYPE = "type"
        const val EXTRA_NAME = "name"
        const val KEY_AUTOMATION = "automation_enabled"
    }
}
