package com.aria.ariacast

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.aria.ariacast.ui.Screen

/**
 * Settings now live in the main window as the third navigation destination. This activity is
 * kept for anything that still launches it (and for the preference keys below): it opens the
 * main window on Settings and closes.
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SCREEN, Screen.SETTINGS.name)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    companion object {
        const val KEY_THEME = "prefs_theme"
        const val KEY_VIDEO_ENABLED = "prefs_video_enabled"
        const val KEY_MULTIROOM_ENABLED = "prefs_multiroom_enabled"
        const val GITHUB_URL = "https://github.com/AriaCast/AriaCast-app"
        const val CONTRIBUTORS_URL = "https://github.com/AriaCast/AriaCast-app/graphs/contributors"
        const val TRANSLATORS_URL = "https://hosted.weblate.org/projects/ariacast/ariacast-app/"
    }
}
