package com.aria.ariacast

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.aria.ariacast.ui.home.SendspinSection
import com.aria.ariacast.ui.LeadingIcon
import com.aria.ariacast.ui.ListGroup
import com.aria.ariacast.ui.ListRow
import com.aria.ariacast.ui.ScreenList
import com.aria.ariacast.ui.SubScreenScaffold
import com.aria.ariacast.ui.SwitchListRow
import com.aria.ariacast.ui.theme.AriaTheme

/** Which protocols discovery looks for. AriaCast itself is always on. */
class ProtocolsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AriaTheme { ProtocolsScreen(onBack = ::finish) } }
    }

    companion object {
        const val KEY_DLNA_ENABLED = "dlna_enabled"
        const val KEY_GOOGLE_CAST_ENABLED = "google_cast_enabled"
        const val KEY_AIRPLAY_ENABLED = "airplay_enabled"
        const val KEY_AIRPLAY2_ENABLED = "airplay2_enabled"
        const val KEY_SNAPCAST_ENABLED = "snapcast_enabled"
        const val KEY_VBAN_ENABLED = "vban_enabled"

        /** True if discovery looks for anything beyond AriaCast; all of these start off. */
        fun anyOptionalEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
            return listOf(KEY_AIRPLAY_ENABLED, KEY_AIRPLAY2_ENABLED, KEY_GOOGLE_CAST_ENABLED, KEY_DLNA_ENABLED, KEY_SNAPCAST_ENABLED, KEY_VBAN_ENABLED)
                .any { prefs.getBoolean(it, false) }
        }
    }
}

@Composable
private fun ProtocolsScreen(onBack: () -> Unit) {
    SubScreenScaffold(title = stringResource(R.string.protocols), onBack = onBack) { padding ->
        ScreenList(Modifier.padding(padding)) {
            item {
                ListGroup {
                    ListRow(
                        stringResource(R.string.ariacast_protocol), supporting = stringResource(R.string.ariacast_protocol_desc),
                        leading = { LeadingIcon(Icons.Filled.Speaker, active = true) },
                        trailing = { Text(stringResource(R.string.protocols_always_on), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) },
                    )
                    ProtocolRow(R.string.airplay_protocol, R.string.airplay_protocol_desc, ProtocolsActivity.KEY_AIRPLAY_ENABLED, Icons.Filled.Wifi)
                    ProtocolRow(R.string.airplay2_protocol, R.string.airplay2_protocol_desc, ProtocolsActivity.KEY_AIRPLAY2_ENABLED, Icons.Filled.Wifi)
                    ProtocolRow(R.string.google_cast_protocol, R.string.google_cast_protocol_desc, ProtocolsActivity.KEY_GOOGLE_CAST_ENABLED, Icons.Filled.Cast)
                    ProtocolRow(R.string.dlna_protocol, R.string.dlna_protocol_desc, ProtocolsActivity.KEY_DLNA_ENABLED, Icons.Filled.Tv)
                    ProtocolRow(R.string.snapcast_protocol, R.string.snapcast_protocol_desc, ProtocolsActivity.KEY_SNAPCAST_ENABLED, Icons.Filled.SpeakerGroup)
                    ProtocolRow(R.string.vban_protocol, R.string.vban_protocol_desc, ProtocolsActivity.KEY_VBAN_ENABLED, Icons.Filled.Speaker)
                }
            }
            item { SendspinSection() }
        }
    }
}

@Composable
private fun ProtocolRow(title: Int, desc: Int, key: String, icon: ImageVector) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE) }
    var on by remember { mutableStateOf(prefs.getBoolean(key, false)) }
    SwitchListRow(stringResource(title), stringResource(desc), on, { on = it; prefs.edit().putBoolean(key, it).apply() }, icon)
}
