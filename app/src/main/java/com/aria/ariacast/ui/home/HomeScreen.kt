package com.aria.ariacast.ui.home

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GroupWork
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aria.ariacast.AirPlayPinsActivity
import com.aria.ariacast.AudioCastService
import com.aria.ariacast.CastDestination
import com.aria.ariacast.CastGroup
import com.aria.ariacast.CastState
import com.aria.ariacast.DiscoveryState
import com.aria.ariacast.NfcWriteActivity
import com.aria.ariacast.PacketLogActivity
import com.aria.ariacast.PluginsActivity
import com.aria.ariacast.ProtocolsActivity
import com.aria.ariacast.R
import com.aria.ariacast.Server
import com.aria.ariacast.companion.CompanionHub
import com.aria.ariacast.companion.CompanionStatus
import com.aria.ariacast.musicassistant.MusicAssistantPanel
import com.aria.ariacast.pipeline.MicrophoneSource
import com.aria.ariacast.pipeline.SourceKind
import com.aria.ariacast.receiver.ReceiverHub
import com.aria.ariacast.ui.AppController
import com.aria.ariacast.ui.CompanionActivity
import com.aria.ariacast.ui.Screen
import com.aria.ariacast.ui.SectionCard
import com.aria.ariacast.ui.StatusDot
import com.aria.ariacast.ui.plugins.PluginPanels
import com.aria.ariacast.ui.theme.StatusColors
import com.aria.ariacast.video.VideoCastActivity

/** The audio source chosen on the Send card, shared with the speaker list and groups. */
class SourceSelection(initial: SourceKind, initialInput: Int?) {
    var source by mutableStateOf(initial)
    var inputDeviceId by mutableStateOf(initialInput)
}

@Composable
private fun rememberSourceSelection(): SourceSelection {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE) }
    return remember {
        SourceSelection(
            SourceKind.fromId(prefs.getString(KEY_SOURCE, null)) ?: SourceKind.SYSTEM_CAPTURE,
            prefs.getInt(KEY_INPUT, -1).takeIf { it >= 0 },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(controller: AppController) {
    val selection = rememberSourceSelection()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = { HomeActions(controller) },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        if (wide) {
            // Tablets / large screens: Send and Receive side by side.
            Row(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    sendItems(controller, selection)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ReceiveCard(controller)
                    PluginPanels()
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                sendItems(controller, selection)
                item { ReceiveCard(controller) }
                item { PluginPanels() }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.sendItems(controller: AppController, selection: SourceSelection) {
    item { Banners(controller) }
    item { SendCard(controller, selection) }
    item { SyncCard(controller) }
    item { MusicAssistantPanel(controller) }
    item { SpeakersHeader(controller) }
    item { SpeakerList(controller, selection) }
    item { GroupsSection(controller, selection) }
}

@Composable
private fun HomeActions(controller: AppController) {
    var menu by remember { mutableStateOf(false) }
    IconButton(onClick = { controller.navigate(Screen.DOCTOR) }) { Icon(Icons.Outlined.HealthAndSafety, stringResource(R.string.doctor_title)) }
    IconButton(onClick = { controller.openSettings() }) { Icon(Icons.Filled.Settings, stringResource(R.string.settings)) }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            listOf(
                R.string.video_cast_title to VideoCastActivity::class.java,
                R.string.companion to CompanionActivity::class.java,
                R.string.plugins to PluginsActivity::class.java,
                R.string.nfc_write_title to NfcWriteActivity::class.java,
                R.string.protocols to ProtocolsActivity::class.java,
                R.string.manage_airplay_pins to AirPlayPinsActivity::class.java,
                R.string.packet_exchange to PacketLogActivity::class.java,
            ).forEach { (label, cls) ->
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { menu = false; controller.openActivity(cls) })
            }
        }
    }
}

@Composable
private fun Banners(controller: AppController) {
    val access by controller.notificationAccess.collectAsState()
    val context = LocalContext.current
    val companion by CompanionHub.get(context).status.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!access) {
            Banner(Icons.Filled.NotificationsActive, stringResource(R.string.banner_notification_access), stringResource(R.string.allow)) {
                controller.requestNotificationAccess()
            }
        }
        if (companion is CompanionStatus.Connected || companion is CompanionStatus.NeedsPermission) {
            Banner(Icons.Filled.Usb, stringResource(R.string.banner_companion), stringResource(R.string.open)) {
                controller.openActivity(CompanionActivity::class.java)
            }
        }
    }
}

@Composable
private fun Banner(icon: ImageVector, text: String, action: String, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), shape = MaterialTheme.shapes.large) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null)
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

// ------------------------------------------------------------------ Send

private const val KEY_SOURCE = "ui_selected_source"

@Composable
private fun SendCard(controller: AppController, selection: SourceSelection) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE) }
    val service by controller.service.collectAsState()
    val s = service
    val state by (s?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(CastState.OFF) }).collectAsState()
    val destinations by (s?.activeDestinations ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<CastDestination>()) }).collectAsState()
    val metadata by (s?.metadata ?: remember { kotlinx.coroutines.flow.MutableStateFlow(null) }).collectAsState()
    val artwork by (s?.artwork ?: remember { kotlinx.coroutines.flow.MutableStateFlow<ByteArray?>(null) }).collectAsState()
    val lastError by (s?.lastError ?: remember { kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }).collectAsState()
    val volume by (s?.volumePercent ?: remember { kotlinx.coroutines.flow.MutableStateFlow(50) }).collectAsState()
    val companionStatus by CompanionHub.get(context).status.collectAsState()
    val receiving by ReceiverHub.get(context).running.collectAsState()
    val source = selection.source
    fun choose(k: SourceKind) { selection.source = k; prefs.edit().putString(KEY_SOURCE, k.id).apply() }
    val casting = state == CastState.CASTING || state == CastState.CONNECTING

    val container = if (state == CastState.CASTING) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    SectionCard(title = null, containerColor = container) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (state == CastState.CASTING) Icons.Filled.CastConnected else Icons.Filled.Cast, null, Modifier.size(28.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.send_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    when (state) {
                        CastState.CASTING -> stringResource(R.string.send_casting_to, destinations.joinToString(", ") { it.name })
                        CastState.CONNECTING -> stringResource(R.string.connecting)
                        CastState.ERROR -> lastError ?: stringResource(R.string.connection_error)
                        else -> stringResource(R.string.send_idle)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state == CastState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (!casting) {
            Text(stringResource(R.string.send_source), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                SourceChip(stringResource(R.string.source_apps), source == SourceKind.SYSTEM_CAPTURE) { choose(SourceKind.SYSTEM_CAPTURE) }
                SourceChip(stringResource(R.string.source_microphone), source == SourceKind.MICROPHONE) { choose(SourceKind.MICROPHONE) }
                if (companionStatus is CompanionStatus.Connected) {
                    SourceChip(stringResource(R.string.companion), source == SourceKind.COMPANION_USB) { choose(SourceKind.COMPANION_USB) }
                }
                if (receiving) {
                    SourceChip(stringResource(R.string.source_received), source == SourceKind.RECEIVER) { choose(SourceKind.RECEIVER) }
                }
            }
            if (source == SourceKind.MICROPHONE) InputPicker(prefs, selection)
            Text(stringResource(R.string.send_hint_tap_speaker), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val m = metadata
            if (m != null && !m.title.isNullOrEmpty()) {
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).combinedClickableCompat { controller.navigate(Screen.NOW_PLAYING) }.padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Artwork(artwork, Modifier.size(56.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.title ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(m.artist ?: "", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, null)
                var dragging by remember { mutableStateOf<Float?>(null) }
                Slider(
                    value = dragging ?: volume.toFloat(), valueRange = 0f..100f,
                    onValueChange = { dragging = it }, onValueChangeFinished = { dragging?.let { v -> s?.setVolume(v.toInt()) }; dragging = null },
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { controller.navigate(Screen.NOW_PLAYING) }) { Text(stringResource(R.string.now_playing)) }
                Button(onClick = { controller.stopCasting() }) { Text(stringResource(R.string.stop)) }
            }
        }
    }
}

@Composable
private fun SourceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

private const val KEY_INPUT = "ui_selected_input"

@Composable
private fun InputPicker(prefs: android.content.SharedPreferences, selection: SourceSelection) {
    val context = LocalContext.current
    val inputs = remember { MicrophoneSource.availableInputs(context) }
    val selected = selection.inputDeviceId ?: -1
    Column {
        inputs.forEach { d ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(selected = selected == d.id, onClick = { selection.inputDeviceId = d.id; prefs.edit().putInt(KEY_INPUT, d.id).apply() })
                Text(d.productName?.toString()?.ifBlank { null } ?: stringResource(R.string.source_builtin_mic))
            }
        }
    }
}

@Composable
fun Artwork(bytes: ByteArray?, modifier: Modifier = Modifier) {
    val bitmap = remember(bytes) { bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() } }
    Box(modifier.clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Card(Modifier.fillMaxSize(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Filled.MusicNote, null) }
            }
        }
    }
}

// ------------------------------------------------------------------ Multiroom sync

@Composable
private fun SyncCard(controller: AppController) {
    val service by controller.service.collectAsState()
    val s = service ?: return
    val destinations by s.activeDestinations.collectAsState()
    val state by s.state.collectAsState()
    if (state != CastState.CASTING || destinations.size < 2) return
    SectionCard(title = stringResource(R.string.multiroom_sync)) {
        Text(stringResource(R.string.multiroom_sync_desc), style = MaterialTheme.typography.bodySmall)
        destinations.forEach { dest ->
            var value by remember(dest.host) { mutableFloatStateOf(dest.delayMs.toFloat()) }
            Text("${dest.name} · ${value.toInt()} ms", style = MaterialTheme.typography.bodyMedium)
            Slider(value = value, onValueChange = { value = it }, valueRange = 0f..2000f, steps = 99,
                onValueChangeFinished = { s.setDelay(dest.host, value.toInt()) })
        }
    }
}

// ------------------------------------------------------------------ Speakers

@Composable
private fun SpeakersHeader(controller: AppController) {
    val state by controller.discoveryState.collectAsState()
    var addDialog by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.speakers), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (state == DiscoveryState.SCANNING) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        IconButton(onClick = { controller.refreshDiscovery() }) { Icon(Icons.Filled.Refresh, stringResource(R.string.refresh)) }
        IconButton(onClick = { addDialog = true }) { Icon(Icons.Filled.Add, stringResource(R.string.add_by_address)) }
    }
    if (addDialog) AddSpeakerDialog(controller) { addDialog = false }
}

@Composable
private fun AddSpeakerDialog(controller: AppController, onDismiss: () -> Unit) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("12889") }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_by_address)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.add_by_address_desc), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(host, { host = it.trim(); error = false }, label = { Text(stringResource(R.string.ip_address)) }, singleLine = true, isError = error,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text(stringResource(R.string.port)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name_optional)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: 12889
                if (controller.addManualServer(host, p, name.ifBlank { host })) onDismiss() else error = true
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SpeakerList(controller: AppController, selection: SourceSelection) {
    val servers by controller.servers.collectAsState()
    val service by controller.service.collectAsState()
    val active by (service?.activeDestinations ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<CastDestination>()) }).collectAsState()
    if (servers.isEmpty()) {
        Text(stringResource(R.string.no_speakers_yet), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Grouped by protocol, so a TV with AirPlay and Cast shows up twice side by side.
        servers.sortedWith(compareBy({ it.platform ?: "" }, { it.name.lowercase() })).forEach { server ->
            val isActive = active.any { it.host == server.host && it.platform == server.platform }
            SpeakerRow(server, isActive,
                onClick = { controller.castTo(listOf(server), selection.source, selection.inputDeviceId) },
                onRemove = { controller.removeServer(server) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpeakerRow(server: Server, active: Boolean, onClick: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { menu = true }),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(platformIcon(server.platform), null)
            Column(Modifier.weight(1f)) {
                Text(server.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${platformLabel(server.platform)} · ${server.host}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusDot(if (active) StatusColors.Connected else StatusColors.Available,
                stringResource(if (active) R.string.status_connected else R.string.status_available))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.remove)) }, leadingIcon = { Icon(Icons.Filled.Delete, null) },
                onClick = { menu = false; onRemove() })
        }
    }
}

fun platformIcon(platform: String?): ImageVector = when (platform) {
    "Google Cast" -> Icons.Filled.Cast
    "DLNA" -> Icons.Filled.Tv
    "AirPlay", "AirPlay2" -> Icons.Filled.Wifi
    else -> Icons.Filled.Speaker
}

@Composable
fun platformLabel(platform: String?): String = when (platform) {
    "AirPlay" -> "AirPlay"
    "AirPlay2" -> "AirPlay 2"
    null -> stringResource(R.string.app_name)
    else -> platform
}

// ------------------------------------------------------------------ Groups (scenes)

@Composable
private fun GroupsSection(controller: AppController, selection: SourceSelection) {
    val enabled by controller.multiroomEnabled.collectAsState()
    if (!enabled) return
    val groups by controller.groups.collectAsState()
    val servers by controller.servers.collectAsState()
    var create by remember { mutableStateOf(false) }
    SectionCard(title = stringResource(R.string.groups_title)) {
        Text(stringResource(R.string.groups_desc), style = MaterialTheme.typography.bodySmall)
        groups.forEach { group ->
            val online = servers.filter { it.host in group.hosts }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.GroupWork, null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(group.name, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.group_online, online.size, group.hosts.size), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(enabled = online.isNotEmpty(), onClick = { controller.castTo(online, selection.source, selection.inputDeviceId) }) {
                    Text(stringResource(R.string.play))
                }
                IconButton(onClick = { controller.deleteGroup(group) }) { Icon(Icons.Filled.Delete, stringResource(R.string.remove)) }
            }
        }
        OutlinedButton(onClick = { create = true }, enabled = servers.isNotEmpty()) { Text(stringResource(R.string.create_speaker_group)) }
    }
    if (create) CreateGroupDialog(servers, onSave = { n, h -> controller.saveGroup(n, h); create = false }) { create = false }
}

@Composable
private fun CreateGroupDialog(servers: List<Server>, onSave: (String, List<String>) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_speaker_group)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.group_name)) }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                servers.distinctBy { it.host }.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = s.host in selected, onCheckedChange = { if (it) selected.add(s.host) else selected.remove(s.host) })
                        Text(s.name)
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank() && selected.isNotEmpty(), onClick = { onSave(name, selected.toList()) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ------------------------------------------------------------------ Receive

@Composable
private fun ReceiveCard(controller: AppController) {
    val context = LocalContext.current
    val hub = remember { ReceiverHub.get(context) }
    val running by hub.running.collectAsState()
    val sender by hub.sender.collectAsState()
    val meta by hub.metadata.collectAsState()
    val bridging by hub.bridging.collectAsState()
    var name by remember { mutableStateOf(hub.name) }
    var playLocally by remember { mutableStateOf(hub.playLocally) }
    var startAtBoot by remember { mutableStateOf(hub.startAtBoot) }
    var forwardDialog by remember { mutableStateOf(false) }

    SectionCard(title = null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Speaker, null, Modifier.size(28.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.receive_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    when {
                        !running -> stringResource(R.string.receive_off)
                        sender == null -> stringResource(R.string.receive_waiting_as, hub.name)
                        else -> stringResource(R.string.receive_from, listOfNotNull(meta.title, meta.artist).joinToString(" · ").ifEmpty { sender!! })
                    },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = running, onCheckedChange = { if (it) hub.start() else hub.stop() })
        }
        if (running) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.receive_name)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(stringResource(R.string.receive_name_hint)) },
                trailingIcon = {
                    if (name != hub.name) TextButton(onClick = { hub.name = name; hub.stop(); hub.start() }) { Text(stringResource(R.string.save)) }
                })
            com.aria.ariacast.ui.SwitchRow(stringResource(R.string.receive_play_locally), stringResource(R.string.receive_play_locally_desc), playLocally, {
                playLocally = it; hub.playLocally = it; if (!it) hub.player.stop()
            })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { forwardDialog = true }, enabled = !bridging) {
                    Text(stringResource(if (bridging) R.string.receive_forwarding else R.string.receive_forward))
                }
                OutlinedButton(onClick = { controller.navigate(Screen.RECEIVE) }) {
                    Icon(Icons.Filled.Fullscreen, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.full_screen))
                }
            }
        }
        com.aria.ariacast.ui.SwitchRow(stringResource(R.string.receive_at_boot), stringResource(R.string.receive_at_boot_desc), startAtBoot, {
            startAtBoot = it; hub.startAtBoot = it
        })
        if (running) {
            var dlnaOn by remember { mutableStateOf(context.getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE).getBoolean(com.aria.ariacast.dlna.DlnaRenderer.KEY_ENABLED, true)) }
            com.aria.ariacast.ui.SwitchRow(stringResource(R.string.dlna_renderer), stringResource(R.string.dlna_renderer_desc), dlnaOn, {
                dlnaOn = it
                context.getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(com.aria.ariacast.dlna.DlnaRenderer.KEY_ENABLED, it).apply()
                if (it) com.aria.ariacast.dlna.DlnaRenderer.get(context).start(hub.name) else com.aria.ariacast.dlna.DlnaRenderer.get(context).stop()
            })
            SendspinSection()
        }
    }
    if (forwardDialog) ForwardDialog(controller) { forwardDialog = false }
}

@Composable
private fun ForwardDialog(controller: AppController, onDismiss: () -> Unit) {
    val servers by controller.servers.collectAsState()
    val selected = remember { mutableStateListOf<Server>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.receive_forward)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.receive_forward_desc), style = MaterialTheme.typography.bodySmall)
                servers.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = s in selected, onCheckedChange = { if (it) selected.add(s) else selected.remove(s) })
                        Text("${s.name} (${platformLabel(s.platform)})")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selected.isNotEmpty(), onClick = { controller.castTo(selected.toList(), SourceKind.RECEIVER); onDismiss() }) {
                Text(stringResource(R.string.receive_forward_start))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ------------------------------------------------------------------ small helpers

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = this.combinedClickable(onClick = onClick)
