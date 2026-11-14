package com.aria.ariacast.ui.home

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GroupWork
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aria.ariacast.AudioCastService
import com.aria.ariacast.CastDestination
import com.aria.ariacast.CastGroup
import com.aria.ariacast.CastState
import com.aria.ariacast.DiscoveryState
import com.aria.ariacast.MediaCommand
import com.aria.ariacast.R
import com.aria.ariacast.Server
import com.aria.ariacast.TrackMetadata
import com.aria.ariacast.companion.CompanionHub
import com.aria.ariacast.companion.CompanionStatus
import com.aria.ariacast.musicassistant.MusicAssistantPanel
import com.aria.ariacast.pipeline.MicrophoneSource
import com.aria.ariacast.pipeline.SourceKind
import com.aria.ariacast.receiver.ReceiverHub
import com.aria.ariacast.ui.AppController
import com.aria.ariacast.ui.CompanionActivity
import com.aria.ariacast.ui.DestinationInsets
import com.aria.ariacast.ui.EmptyState
import com.aria.ariacast.ui.LeadingIcon
import com.aria.ariacast.ui.ListBlock
import com.aria.ariacast.ui.ListGroup
import com.aria.ariacast.ui.ListRow
import com.aria.ariacast.ui.Screen
import com.aria.ariacast.ui.ScreenList
import com.aria.ariacast.ui.SectionHeader
import com.aria.ariacast.ui.StatusBanner
import com.aria.ariacast.ui.SubScreenScaffold
import com.aria.ariacast.ui.plugins.PluginPanels
import kotlinx.coroutines.flow.MutableStateFlow

/** The audio source chosen on the Send screen, shared with the speaker list and groups. */
class SourceSelection(initial: SourceKind, initialInput: Int?) {
    var source by mutableStateOf(initial)
    var inputDeviceId by mutableStateOf(initialInput)
}

private const val KEY_SOURCE = "ui_selected_source"
private const val KEY_INPUT = "ui_selected_input"

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

/** Send destination: where the audio goes. Source on top, then speakers, groups and panels. */
@Composable
fun SendScreen(controller: AppController) {
    val selection = rememberSourceSelection()
    SubScreenScaffold(title = stringResource(R.string.app_name), onBack = null, contentWindowInsets = DestinationInsets) { padding ->
        ScreenList(Modifier.padding(padding)) {
            item { Notices(controller) }
            item { SourcePicker(controller, selection) }
            item { Speakers(controller, selection) }
            item { Groups(controller, selection) }
            item { MusicAssistantPanel(controller) }
            item { PluginPanels() }
        }
    }
}

// ------------------------------------------------------------------ notices

@Composable
private fun Notices(controller: AppController) {
    val context = LocalContext.current
    val access by controller.notificationAccess.collectAsState()
    val companion by CompanionHub.get(context).status.collectAsState()
    val service by controller.service.collectAsState()
    val state by (service?.state ?: remember { MutableStateFlow(CastState.OFF) }).collectAsState()
    val lastError by (service?.lastError ?: remember { MutableStateFlow<String?>(null) }).collectAsState()
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state == CastState.ERROR) {
            StatusBanner(Icons.Filled.ErrorOutline, lastError ?: stringResource(R.string.connection_error), stringResource(R.string.send_diagnose), error = true) {
                controller.navigate(Screen.DOCTOR)
            }
        }
        if (!access) {
            StatusBanner(Icons.Filled.NotificationsActive, stringResource(R.string.banner_notification_access), stringResource(R.string.allow)) {
                controller.requestNotificationAccess()
            }
        }
        if ((companion as? CompanionStatus.Connected)?.bluetooth == false || companion is CompanionStatus.NeedsPermission) {
            StatusBanner(Icons.Filled.Usb, stringResource(R.string.banner_companion), stringResource(R.string.open)) {
                controller.openActivity(CompanionActivity::class.java)
            }
        }
    }
}

// ------------------------------------------------------------------ source

@Composable
private fun SourcePicker(controller: AppController, selection: SourceSelection) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE) }
    val companionStatus by CompanionHub.get(context).status.collectAsState()
    val receiving by ReceiverHub.get(context).running.collectAsState()
    fun choose(k: SourceKind) { selection.source = k; prefs.edit().putString(KEY_SOURCE, k.id).apply() }

    Column {
        SectionHeader(stringResource(R.string.send_source))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SourceChip(stringResource(R.string.source_apps), Icons.Filled.MusicNote, selection.source == SourceKind.SYSTEM_CAPTURE) { choose(SourceKind.SYSTEM_CAPTURE) }
            SourceChip(stringResource(R.string.source_microphone), Icons.Outlined.Mic, selection.source == SourceKind.MICROPHONE) { choose(SourceKind.MICROPHONE) }
            if (companionStatus is CompanionStatus.Connected) {
                SourceChip(stringResource(R.string.companion), Icons.Filled.Usb, selection.source == SourceKind.COMPANION_USB) { choose(SourceKind.COMPANION_USB) }
            }
            if (receiving) {
                SourceChip(stringResource(R.string.source_received), Icons.Filled.Speaker, selection.source == SourceKind.RECEIVER) { choose(SourceKind.RECEIVER) }
            }
        }
        AnimatedVisibility(selection.source == SourceKind.MICROPHONE, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            InputPicker(prefs, selection)
        }
    }
}

@Composable
private fun SourceChip(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label) },
        leadingIcon = { Icon(icon, null, Modifier.size(FilterChipDefaults.IconSize)) },
    )
}

@Composable
private fun InputPicker(prefs: android.content.SharedPreferences, selection: SourceSelection) {
    val context = LocalContext.current
    val inputs = remember { MicrophoneSource.availableInputs(context) }
    val selected = selection.inputDeviceId ?: -1
    ListGroup(Modifier.padding(top = 8.dp)) {
        inputs.forEach { d ->
            val choose = { selection.inputDeviceId = d.id; prefs.edit().putInt(KEY_INPUT, d.id).apply() }
            ListRow(
                headline = d.productName?.toString()?.ifBlank { null } ?: stringResource(R.string.source_builtin_mic),
                leading = { RadioButton(selected = selected == d.id, onClick = null) },
                onClick = choose,
            )
        }
    }
}

// ------------------------------------------------------------------ speakers

@Composable
private fun Speakers(controller: AppController, selection: SourceSelection) {
    val servers by controller.servers.collectAsState()
    val discovery by controller.discoveryState.collectAsState()
    val service by controller.service.collectAsState()
    val active by (service?.activeDestinations ?: remember { MutableStateFlow(emptyList<CastDestination>()) }).collectAsState()
    var addDialog by remember { mutableStateOf(false) }
    fun isActive(s: Server) = active.any { it.host == s.host && it.platform == s.platform }

    Column {
        SectionHeader(stringResource(R.string.speakers)) {
            IconButton(onClick = { controller.refreshDiscovery() }) { Icon(Icons.Filled.Refresh, stringResource(R.string.refresh)) }
            IconButton(onClick = { addDialog = true }) { Icon(Icons.Filled.Add, stringResource(R.string.add_by_address)) }
        }
        // Scanning shows as a hairline progress under the header, never a spinner in the list.
        Box(Modifier.fillMaxWidth().height(4.dp).padding(horizontal = 16.dp)) {
            if (discovery == DiscoveryState.SCANNING) LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)))
        }
        if (servers.isEmpty()) {
            EmptyState(
                Icons.Filled.Speaker, stringResource(R.string.speakers_empty_title), stringResource(R.string.speakers_empty_body),
                action = { TextButton(onClick = { addDialog = true }) { Text(stringResource(R.string.add_by_address)) } },
            )
        } else {
            // Active first; then by name, so a TV offering AirPlay and Cast shows its two entries side by side.
            val sorted = servers.sortedWith(compareBy<Server>({ !isActive(it) }, { it.name.lowercase() }, { it.platform ?: "" }))
            ListGroup {
                sorted.forEach { server ->
                    SpeakerRow(server, isActive(server),
                        onClick = { controller.castTo(listOf(server), selection.source, selection.inputDeviceId) },
                        onRemove = { controller.removeServer(server) })
                }
            }
        }
    }
    if (addDialog) AddSpeakerDialog(controller) { addDialog = false }
}

@Composable
private fun SpeakerRow(server: Server, active: Boolean, onClick: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        ListRow(
            headline = server.name,
            supporting = if (active) "${stringResource(R.string.status_playing)} · ${platformLabel(server.platform)}"
            else "${platformLabel(server.platform)} · ${server.host}",
            leading = { LeadingIcon(platformIcon(server.platform), active = active) },
            trailing = if (active) { { Icon(Icons.Filled.GraphicEq, null, tint = MaterialTheme.colorScheme.primary) } } else null,
            selected = active,
            onLongClick = { menu = true },
            onClick = onClick,
        )
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
    "Snapcast", "Sendspin" -> Icons.Filled.SpeakerGroup
    else -> Icons.Filled.Speaker
}

@Composable
fun platformLabel(platform: String?): String = when (platform) {
    "AirPlay" -> "AirPlay"
    "AirPlay2" -> "AirPlay 2"
    null -> stringResource(R.string.app_name)
    else -> platform
}

@Composable
private fun AddSpeakerDialog(controller: AppController, onDismiss: () -> Unit) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("12889") }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Add, null) },
        title = { Text(stringResource(R.string.add_by_address)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.add_by_address_desc), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(host, { host = it.trim(); error = false }, label = { Text(stringResource(R.string.ip_address)) }, singleLine = true, isError = error,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text(stringResource(R.string.port)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name_optional)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = host.isNotBlank(), onClick = {
                val p = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: 12889
                if (controller.addManualServer(host, p, name.ifBlank { host })) onDismiss() else error = true
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ------------------------------------------------------------------ groups

@Composable
private fun Groups(controller: AppController, selection: SourceSelection) {
    val enabled by controller.multiroomEnabled.collectAsState()
    if (!enabled) return
    val groups by controller.groups.collectAsState()
    val servers by controller.servers.collectAsState()
    var create by remember { mutableStateOf(false) }
    ListGroup(
        Modifier.padding(top = 8.dp),
        title = stringResource(R.string.groups_title),
        actions = {
            IconButton(onClick = { create = true }, enabled = servers.isNotEmpty()) { Icon(Icons.Filled.Add, stringResource(R.string.create_speaker_group)) }
        },
    ) {
        if (groups.isEmpty()) {
            ListBlock { Text(stringResource(R.string.groups_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        groups.forEach { group -> GroupRow(group, servers, onPlay = { controller.castTo(it, selection.source, selection.inputDeviceId) }, onDelete = { controller.deleteGroup(group) }) }
    }
    if (create) CreateGroupDialog(servers, onSave = { n, h -> controller.saveGroup(n, h); create = false }) { create = false }
}

@Composable
private fun GroupRow(group: CastGroup, servers: List<Server>, onPlay: (List<Server>) -> Unit, onDelete: () -> Unit) {
    val online = servers.filter { it.host in group.hosts }
    var menu by remember { mutableStateOf(false) }
    Box {
        ListRow(
            headline = group.name,
            supporting = stringResource(R.string.group_online, online.size, group.hosts.size),
            leading = { LeadingIcon(Icons.Filled.GroupWork) },
            trailing = {
                IconButton(onClick = { onPlay(online) }, enabled = online.isNotEmpty()) { Icon(Icons.Filled.PlayArrow, stringResource(R.string.play)) }
            },
            enabled = true,
            onLongClick = { menu = true },
            onClick = { if (online.isNotEmpty()) onPlay(online) },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.remove)) }, leadingIcon = { Icon(Icons.Filled.Delete, null) },
                onClick = { menu = false; onDelete() })
        }
    }
}

@Composable
private fun CreateGroupDialog(servers: List<Server>, onSave: (String, List<String>) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.GroupWork, null) },
        title = { Text(stringResource(R.string.create_speaker_group)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.group_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                servers.distinctBy { it.host }.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable { if (s.host in selected) selected.remove(s.host) else selected.add(s.host) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = s.host in selected, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                        Text(s.name)
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank() && selected.isNotEmpty(), onClick = { onSave(name, selected.toList()) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ------------------------------------------------------------------ mini-player

/**
 * What is playing and where, docked above the navigation bar on every destination while a
 * cast runs. Tap opens Now Playing.
 */
@Composable
fun MiniPlayer(controller: AppController, modifier: Modifier = Modifier) {
    val service by controller.service.collectAsState()
    val s = service
    val state by (s?.state ?: remember { MutableStateFlow(CastState.OFF) }).collectAsState()
    val destinations by (s?.activeDestinations ?: remember { MutableStateFlow(emptyList<CastDestination>()) }).collectAsState()
    val metadata by (s?.metadata ?: remember { MutableStateFlow<TrackMetadata?>(null) }).collectAsState()
    val artwork by (s?.artwork ?: remember { MutableStateFlow<ByteArray?>(null) }).collectAsState()
    val visible = state == CastState.CASTING || state == CastState.CONNECTING

    AnimatedVisibility(
        visible, modifier = modifier,
        enter = slideInVertically { it / 2 } + fadeIn(), exit = slideOutVertically { it / 2 } + fadeOut(),
    ) {
      Box {
        // Soft, translucent shadow under the card so it floats over the list (platform shadows
        // all but vanish on dark surfaces, so this one is drawn: a blurred, offset rounded shape).
        Box(
            Modifier.matchParentSize().offset(y = 6.dp)
                .blur(20.dp, BlurredEdgeTreatment.Unbounded)
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(24.dp))
        )
        Surface(
            Modifier.fillMaxWidth()
                .shadow(8.dp, RoundedCornerShape(20.dp), ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.4f))
                .clip(RoundedCornerShape(20.dp)).clickable { controller.navigate(Screen.NOW_PLAYING) },
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(20.dp),
        ) {
            Column {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Artwork(artwork, Modifier.size(48.dp), corner = 12.dp)
                    Column(Modifier.weight(1f)) {
                        val m = metadata
                        Text(
                            if (state == CastState.CONNECTING) stringResource(R.string.connecting) else m?.title?.ifBlank { null } ?: stringResource(R.string.now_playing),
                            style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(R.string.now_playing_on, destinations.joinToString(", ") { it.name }),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (metadata != null) {
                        IconButton(onClick = { s?.sendMediaCommand(MediaCommand.TOGGLE) }) {
                            Icon(if (metadata?.isPlaying == true) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(R.string.play_pause))
                        }
                    }
                    IconButton(onClick = { controller.stopCasting() }) { Icon(Icons.Filled.Stop, stringResource(R.string.stop)) }
                }
                if (state == CastState.CONNECTING) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            }
        }
      }
    }
}

// ------------------------------------------------------------------ artwork

/** Album artwork, or a quiet note glyph on a tonal tile when there is none. */
@Composable
fun Artwork(bytes: ByteArray?, modifier: Modifier = Modifier, corner: androidx.compose.ui.unit.Dp = 16.dp) {
    val bitmap = remember(bytes) { bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() } }
    Box(
        modifier.clip(RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}
