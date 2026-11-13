package com.aria.ariacast.ui

import android.Manifest
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aria.ariacast.AudioCastService
import com.aria.ariacast.R
import com.aria.ariacast.companion.CompanionHub
import com.aria.ariacast.companion.CompanionStatus
import com.aria.ariacast.companion.FirmwareUpdater
import com.aria.ariacast.ui.theme.AriaTheme

/** AriaCompanion 2 status and settings (docs/ariacompanion-2.md §6.6). */
class CompanionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        CompanionHub.get(this).rescan()
        setContent { AriaTheme { CompanionScreen(onBack = ::finish) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val hub = remember { CompanionHub.get(context) }
    val status by hub.status.collectAsStateWithLifecycle()
    val stats by hub.stats.collectAsStateWithLifecycle()
    val gate by hub.gate.state.collectAsStateWithLifecycle()
    val prefs = remember { context.getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE) }
    var includeGames by remember { mutableStateOf(prefs.getBoolean(CompanionHub.KEY_INCLUDE_GAMES, false)) }
    var autoCast by remember { mutableStateOf(prefs.getBoolean(CompanionHub.KEY_AUTO_CAST, true)) }
    var phonePermission by remember {
        mutableStateOf(androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }
    val requestPhone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { phonePermission = it }
    val updateMessage by FirmwareUpdater.progress.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.companion)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(title = null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(
                        if (status is CompanionStatus.Connected) Icons.Filled.Usb else Icons.Filled.UsbOff,
                        contentDescription = null, modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(statusTitle(status), style = MaterialTheme.typography.titleLarge)
                        Text(statusSubtitle(status), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (status is CompanionStatus.NeedsPermission) {
                    Button(onClick = { hub.rescan() }) { Text(stringResource(R.string.companion_allow_usb)) }
                }
            }

            val connected = status as? CompanionStatus.Connected
            if (connected != null) {
                SectionCard(title = stringResource(R.string.companion_signal)) {
                    val s = stats
                    val peak = maxOf(s?.peakLeft ?: 0, s?.peakRight ?: 0) / 32767f
                    LevelMeter(peak)
                    KeyValueRow(stringResource(R.string.companion_firmware), connected.info.firmwareVersionString)
                    KeyValueRow(stringResource(R.string.companion_overruns), (s?.overruns ?: 0).toString())
                    KeyValueRow(stringResource(R.string.companion_received), "%.1f MB".format((s?.bulkBytes ?: 0) / 1_048_576f))
                    Text(
                        if (gate.blocked) stringResource(R.string.companion_gate_closed, gate.reason ?: "") else stringResource(R.string.companion_gate_open),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (gate.blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionCard(title = stringResource(R.string.settings)) {
                SwitchRow(stringResource(R.string.companion_auto_cast), stringResource(R.string.companion_auto_cast_desc), autoCast, {
                    autoCast = it; prefs.edit().putBoolean(CompanionHub.KEY_AUTO_CAST, it).apply()
                }, Icons.Filled.Usb)
                SwitchRow(stringResource(R.string.companion_include_games), stringResource(R.string.companion_include_games_desc), includeGames, {
                    includeGames = it; prefs.edit().putBoolean(CompanionHub.KEY_INCLUDE_GAMES, it).apply()
                }, Icons.Filled.SportsEsports)
                if (!phonePermission) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Filled.Call, null)
                        Text(stringResource(R.string.companion_calls_hint), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedButton(onClick = { requestPhone.launch(Manifest.permission.READ_PHONE_STATE) }) {
                        Text(stringResource(R.string.companion_calls_allow))
                    }
                }
            }

            SectionCard(title = stringResource(R.string.companion_firmware)) {
                Text(stringResource(R.string.companion_firmware_desc, FirmwareUpdater.bundledVersion(context) ?: "—"), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(
                    enabled = connected != null || status is CompanionStatus.Bootloader,
                    onClick = { FirmwareUpdater.start(context) },
                ) { Text(stringResource(R.string.companion_update_firmware)) }
                updateMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun statusTitle(s: CompanionStatus): String = when (s) {
    CompanionStatus.Absent -> stringResource(R.string.companion_absent)
    CompanionStatus.NeedsPermission -> stringResource(R.string.companion_needs_permission)
    is CompanionStatus.Connected -> stringResource(R.string.companion_connected)
    CompanionStatus.Switching -> stringResource(R.string.companion_switching)
    CompanionStatus.Bootloader -> stringResource(R.string.companion_bootloader)
    is CompanionStatus.Error -> stringResource(R.string.companion_error)
}

@Composable
private fun statusSubtitle(s: CompanionStatus): String = when (s) {
    CompanionStatus.Absent -> stringResource(R.string.companion_absent_desc)
    CompanionStatus.NeedsPermission -> stringResource(R.string.companion_needs_permission_desc)
    is CompanionStatus.Connected -> when {
        s.streaming -> stringResource(R.string.companion_mode_streaming)
        s.speaker -> stringResource(R.string.companion_mode_speaker)
        else -> stringResource(R.string.companion_mode_private)
    }
    CompanionStatus.Switching -> stringResource(R.string.companion_switching_desc)
    CompanionStatus.Bootloader -> stringResource(R.string.companion_bootloader_desc)
    is CompanionStatus.Error -> s.message
}
