package com.aria.ariacast

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aria.ariacast.ui.EmptyState
import com.aria.ariacast.ui.LeadingIcon
import com.aria.ariacast.ui.ListGroup
import com.aria.ariacast.ui.ListRow
import com.aria.ariacast.ui.ScreenList
import com.aria.ariacast.ui.SubScreenScaffold
import com.aria.ariacast.ui.theme.AriaTheme

/** Saved AirPlay secrets: receiver passwords and pairings made with an on-screen code. */
class AirPlayPinsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AriaTheme { AirPlayPinsScreen(onBack = ::finish) } }
    }

    /** One stored AirPlay secret: a receiver password, or a pairing made with an onscreen code. */
    data class Entry(val key: String, val title: String, val kind: Kind) {
        enum class Kind { PASSWORD, PAIRED_AIRPLAY2, PAIRED_AIRPLAY }
        val editable get() = kind == Kind.PASSWORD
    }

    companion object {
        fun load(context: Context): List<Entry> {
            val prefs = context.getSharedPreferences(AudioCastService.SECURE_PREFS_NAME, Context.MODE_PRIVATE)
            // PINs are one-time codes: pairing now stores long-term keys instead, so any
            // PIN saved by older versions is useless - drop it.
            prefs.all.keys.filter { it.startsWith("airplay2_pin_") }.takeIf { it.isNotEmpty() }?.let { stale ->
                prefs.edit().apply { stale.forEach { remove(it) } }.apply()
            }
            return prefs.all.mapNotNull { (key, value) ->
                when {
                    key.startsWith(AudioCastService.KEY_AIRPLAY_PASSWORD_PREFIX) ->
                        Entry(key, key.removePrefix(AudioCastService.KEY_AIRPLAY_PASSWORD_PREFIX), Entry.Kind.PASSWORD)
                    key.startsWith(AudioCastService.KEY_HAP_PAIRING_PREFIX) ->
                        Entry(key, key.removePrefix(AudioCastService.KEY_HAP_PAIRING_PREFIX), Entry.Kind.PAIRED_AIRPLAY2)
                    key.startsWith(AudioCastService.KEY_RAOP_LEGACY_PREFIX) && value == true ->
                        Entry(key, key.removePrefix(AudioCastService.KEY_RAOP_LEGACY_PREFIX), Entry.Kind.PAIRED_AIRPLAY)
                    else -> null
                }
            }.sortedBy { it.title }
        }
    }
}

@Composable
private fun AirPlayPinsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(AudioCastService.SECURE_PREFS_NAME, Context.MODE_PRIVATE) }
    var version by remember { mutableIntStateOf(0) }
    val entries = remember(version) { AirPlayPinsActivity.load(context) }
    var editing by remember { mutableStateOf<AirPlayPinsActivity.Entry?>(null) }
    var deleting by remember { mutableStateOf<AirPlayPinsActivity.Entry?>(null) }

    SubScreenScaffold(title = stringResource(R.string.manage_airplay_pins), onBack = onBack) { padding ->
        ScreenList(Modifier.padding(padding)) {
            item {
                if (entries.isEmpty()) {
                    EmptyState(Icons.Filled.Key, stringResource(R.string.no_pins_saved), stringResource(R.string.no_pins_saved_desc), Modifier.padding(top = 8.dp))
                } else {
                    ListGroup {
                        entries.forEach { e ->
                            ListRow(
                                headline = e.title,
                                supporting = stringResource(
                                    when (e.kind) {
                                        AirPlayPinsActivity.Entry.Kind.PASSWORD -> R.string.pin_password
                                        AirPlayPinsActivity.Entry.Kind.PAIRED_AIRPLAY2 -> R.string.pin_paired_airplay2
                                        AirPlayPinsActivity.Entry.Kind.PAIRED_AIRPLAY -> R.string.pin_paired_airplay
                                    }
                                ),
                                leading = { LeadingIcon(if (e.editable) Icons.Filled.Key else Icons.Filled.Link) },
                                trailing = {
                                    Row {
                                        if (e.editable) IconButton(onClick = { editing = e }) { Icon(Icons.Filled.Edit, stringResource(R.string.edit_pin)) }
                                        IconButton(onClick = { deleting = e }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete_pin)) }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { e ->
        var secret by remember(e) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editing = null },
            icon = { Icon(Icons.Filled.Key, null) },
            title = { Text(stringResource(R.string.airplay_password_title)) },
            text = {
                OutlinedTextField(secret, { secret = it }, label = { Text(e.title) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            },
            confirmButton = {
                TextButton(enabled = secret.isNotEmpty(), onClick = { prefs.edit().putString(e.key, secret).apply(); editing = null; version++ }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            icon = { Icon(Icons.Filled.Delete, null) },
            title = { Text(stringResource(R.string.delete_pin)) },
            text = { Text(e.title) },
            confirmButton = { TextButton(onClick = { prefs.edit().remove(e.key).apply(); deleting = null; version++ }) { Text(stringResource(R.string.delete_pin)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
