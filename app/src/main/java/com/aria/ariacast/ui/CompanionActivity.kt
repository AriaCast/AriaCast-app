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
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import com.aria.ariacast.companion.CompanionBt
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.fillMaxWidth

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

    SubScreenScaffold(title = stringResource(R.string.companion), onBack = onBack) { padding ->
        ScreenList(Modifier.padding(padding)) {
            item {
                val connectedNow = status is CompanionStatus.Connected
                Surface(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(28.dp),
                    color = if (connectedNow) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            LeadingIcon(
                                when {
                                    (status as? CompanionStatus.Connected)?.bluetooth == true -> Icons.Filled.BluetoothConnected
                                    connectedNow -> Icons.Filled.Usb
                                    else -> Icons.Filled.UsbOff
                                },
                                active = connectedNow,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(statusTitle(status), style = MaterialTheme.typography.titleLarge)
                                Text(statusSubtitle(status), style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current.copy(alpha = 0.8f))
                            }
                        }
                        if (status is CompanionStatus.NeedsPermission) {
                            Button(onClick = { hub.rescan() }) { Text(stringResource(R.string.companion_allow_usb)) }
                        }
                    }
                }
            }

            val connected = status as? CompanionStatus.Connected
            if (connected != null) {
                item {
                    ListGroup(title = stringResource(R.string.companion_signal)) {
                        val s = stats
                        val peak = maxOf(s?.peakLeft ?: 0, s?.peakRight ?: 0) / 32767f
                        ListBlock {
                            LevelMeter(peak)
                            KeyValueRow(stringResource(R.string.companion_firmware), connected.info.firmwareVersionString)
                            KeyValueRow(stringResource(R.string.companion_overruns), (s?.overruns ?: 0).toString())
                            KeyValueRow(stringResource(R.string.companion_received), "%.1f MB".format((s?.bulkBytes ?: 0) / 1_048_576f))
                        }
                        ListRow(
                            headline = if (gate.blocked) stringResource(R.string.companion_gate_closed, gate.reason ?: "") else stringResource(R.string.companion_gate_open),
                            leading = { LeadingIcon(if (gate.blocked) Icons.Filled.Block else Icons.Filled.MusicNote, active = !gate.blocked) },
                        )
                    }
                }
            }

            item {
                ListGroup(title = stringResource(R.string.settings)) {
                    SwitchListRow(stringResource(R.string.companion_auto_cast), stringResource(R.string.companion_auto_cast_desc), autoCast, {
                        autoCast = it; prefs.edit().putBoolean(CompanionHub.KEY_AUTO_CAST, it).apply()
                    }, Icons.Filled.Usb)
                    SwitchListRow(stringResource(R.string.companion_include_games), stringResource(R.string.companion_include_games_desc), includeGames, {
                        includeGames = it; prefs.edit().putBoolean(CompanionHub.KEY_INCLUDE_GAMES, it).apply()
                    }, Icons.Filled.SportsEsports)
                    if (!phonePermission) {
                        ListRow(
                            headline = stringResource(R.string.companion_calls_hint),
                            leading = { LeadingIcon(Icons.Filled.Call) },
                            trailing = { TextButton(onClick = { requestPhone.launch(Manifest.permission.READ_PHONE_STATE) }) { Text(stringResource(R.string.companion_calls_allow)) } },
                        )
                    }
                }
            }

            item { BluetoothBoard(hub, status) }

            item {
                ListGroup(title = stringResource(R.string.companion_firmware)) {
                    if (connected?.bluetooth == true) {
                        ListRow(
                            headline = connected.info.firmwareVersionString,
                            supporting = stringResource(R.string.companion_bt_firmware),
                            leading = { LeadingIcon(Icons.Filled.SystemUpdate) },
                        )
                    } else {
                        ListRow(
                            headline = stringResource(R.string.companion_update_firmware),
                            supporting = updateMessage ?: stringResource(R.string.companion_firmware_desc, FirmwareUpdater.bundledVersion(context) ?: "—"),
                            leading = { LeadingIcon(Icons.Filled.SystemUpdate) },
                            enabled = connected != null || status is CompanionStatus.Bootloader,
                            onClick = { FirmwareUpdater.start(context) },
                        )
                    }
                }
            }
        }
    }
}

/** The ESP32 board: permission, which paired board to use, pairing a new one, link state. */
@SuppressLint("MissingPermission")
@Composable
private fun BluetoothBoard(hub: CompanionHub, status: CompanionStatus) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(hub.canUseBluetooth()) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
        if (granted) hub.startBluetooth()
    }
    var address by remember { mutableStateOf(hub.bluetoothAddress) }
    var picker by remember { mutableStateOf(false) }
    val connecting by hub.bluetoothConnecting.collectAsStateWithLifecycle()
    val adapter = remember { context.getSystemService(BluetoothManager::class.java)?.adapter }
    val linked = (status as? CompanionStatus.Connected)?.bluetooth == true

    ListGroup(title = stringResource(R.string.companion_bt_title)) {
        if (!allowed) {
            ListRow(
                stringResource(R.string.companion_bt_permission), supporting = stringResource(R.string.companion_bt_permission_desc),
                leading = { LeadingIcon(Icons.Filled.Bluetooth) },
            ) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) askPermission.launch(Manifest.permission.BLUETOOTH_CONNECT) }
        } else {
            val name = address?.let { a -> runCatching { adapter?.getRemoteDevice(a)?.name }.getOrNull() ?: a }
            ListRow(
                headline = name ?: stringResource(R.string.companion_bt_choose),
                supporting = when {
                    address == null -> stringResource(R.string.companion_bt_choose_desc)
                    linked -> stringResource(R.string.companion_bt_linked)
                    status is CompanionStatus.Connected -> stringResource(R.string.companion_bt_usb_priority)
                    connecting -> stringResource(R.string.companion_bt_searching)
                    else -> stringResource(R.string.companion_bt_offline)
                },
                leading = { LeadingIcon(if (linked) Icons.Filled.BluetoothConnected else Icons.Filled.Bluetooth, active = linked) },
                trailing = if (connecting) { { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) } } else null,
                selected = linked,
            ) { picker = true }
            ListRow(
                stringResource(R.string.companion_bt_pair), supporting = stringResource(R.string.companion_bt_pair_desc),
                leading = { LeadingIcon(Icons.Filled.Add) },
            ) { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            if (address != null) {
                ListRow(stringResource(R.string.companion_bt_forget), leading = { LeadingIcon(Icons.Filled.LinkOff) }) {
                    address = null; hub.setBluetoothDevice(null)
                }
            }
        }
    }

    if (picker) {
        val boards = remember { runCatching { adapter?.bondedDevices?.filter(CompanionBt::looksLikeCompanion) }.getOrNull().orEmpty() }
        com.aria.ariacast.ui.settings.ChoiceDialog(
            title = stringResource(R.string.companion_bt_choose),
            options = boards.map { it.address to (it.name ?: it.address) },
            selected = address ?: "",
            onSelect = { a -> picker = false; address = a; hub.setBluetoothDevice(a) },
            onDismiss = { picker = false },
        )
        if (boards.isEmpty()) {
            LaunchedEffect(Unit) {
                android.widget.Toast.makeText(context, context.getString(R.string.companion_bt_none_paired), android.widget.Toast.LENGTH_LONG).show()
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
    CompanionStatus.Absent -> stringResource(R.string.companion_absent_desc_bt)
    CompanionStatus.NeedsPermission -> stringResource(R.string.companion_needs_permission_desc)
    is CompanionStatus.Connected -> stringResource(if (s.bluetooth) R.string.companion_via_bluetooth else R.string.companion_via_usb) + " · " + when {
        s.streaming -> stringResource(R.string.companion_mode_streaming)
        s.speaker -> stringResource(R.string.companion_mode_speaker)
        else -> stringResource(R.string.companion_mode_private)
    }
    CompanionStatus.Switching -> stringResource(R.string.companion_switching_desc)
    CompanionStatus.Bootloader -> stringResource(R.string.companion_bootloader_desc)
    is CompanionStatus.Error -> s.message
}
