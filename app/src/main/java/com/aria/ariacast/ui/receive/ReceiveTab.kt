package com.aria.ariacast.ui.receive

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aria.ariacast.AudioCastService
import com.aria.ariacast.R
import com.aria.ariacast.Server
import com.aria.ariacast.dlna.DlnaRenderer
import com.aria.ariacast.pipeline.SourceKind
import com.aria.ariacast.receiver.ReceiverHub
import com.aria.ariacast.ui.AppController
import com.aria.ariacast.ui.DestinationInsets
import com.aria.ariacast.ui.LeadingIcon
import com.aria.ariacast.ui.ListBlock
import com.aria.ariacast.ui.ListGroup
import com.aria.ariacast.ui.ListRow
import com.aria.ariacast.ui.Screen
import com.aria.ariacast.ui.ScreenList
import com.aria.ariacast.ui.SubScreenScaffold
import com.aria.ariacast.ui.SwitchListRow
import com.aria.ariacast.ui.home.Artwork
import com.aria.ariacast.ui.home.SendspinSection
import com.aria.ariacast.ui.home.platformLabel

/** Receive destination: turn this device into a speaker, and everything that goes with it. */
@Composable
fun ReceiveTab(controller: AppController) {
    val context = LocalContext.current
    val hub = remember { ReceiverHub.get(context) }
    val prefs = remember { context.getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE) }
    val running by hub.running.collectAsState()
    val bridging by hub.bridging.collectAsState()
    var name by remember { mutableStateOf(hub.name) }
    var playLocally by remember { mutableStateOf(hub.playLocally) }
    var startAtBoot by remember { mutableStateOf(hub.startAtBoot) }
    var dlnaOn by remember { mutableStateOf(prefs.getBoolean(DlnaRenderer.KEY_ENABLED, true)) }
    var forwardDialog by remember { mutableStateOf(false) }

    SubScreenScaffold(title = stringResource(R.string.nav_receive), onBack = null, contentWindowInsets = DestinationInsets) { padding ->
        ScreenList(Modifier.padding(padding)) {
            item { ReceiverHero(hub, running) }

            item {
                AnimatedVisibility(running, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ListGroup(title = stringResource(R.string.receive_options)) {
                            ListBlock {
                                OutlinedTextField(
                                    name, { name = it }, label = { Text(stringResource(R.string.receive_name)) }, singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    supportingText = { Text(stringResource(R.string.receive_name_hint)) },
                                    trailingIcon = {
                                        if (name != hub.name && name.isNotBlank()) TextButton(onClick = { hub.name = name; hub.stop(); hub.start() }) { Text(stringResource(R.string.save)) }
                                    },
                                )
                            }
                            SwitchListRow(stringResource(R.string.receive_play_locally), stringResource(R.string.receive_play_locally_desc), playLocally, {
                                playLocally = it; hub.playLocally = it; if (!it) hub.player.stop()
                            }, Icons.Filled.VolumeUp)
                            SwitchListRow(stringResource(R.string.dlna_renderer), stringResource(R.string.dlna_renderer_desc), dlnaOn, {
                                dlnaOn = it
                                prefs.edit().putBoolean(DlnaRenderer.KEY_ENABLED, it).apply()
                                if (it) DlnaRenderer.get(context).start(hub.name) else DlnaRenderer.get(context).stop()
                            }, Icons.Filled.Tv)
                        }
                        ListGroup {
                            ListRow(
                                headline = stringResource(if (bridging) R.string.receive_forwarding else R.string.receive_forward),
                                supporting = stringResource(R.string.receive_forward_desc),
                                leading = { LeadingIcon(Icons.AutoMirrored.Filled.CallSplit, active = bridging) },
                                selected = bridging,
                                enabled = !bridging,
                                onClick = { forwardDialog = true },
                            )
                            ListRow(
                                headline = stringResource(R.string.receive_display),
                                supporting = stringResource(R.string.receive_display_desc),
                                leading = { LeadingIcon(Icons.Filled.Fullscreen) },
                                onClick = { controller.navigate(Screen.RECEIVER_DISPLAY) },
                            )
                        }
                        SendspinSection()
                    }
                }
            }

            item {
                ListGroup(Modifier.padding(top = 8.dp)) {
                    SwitchListRow(stringResource(R.string.receive_at_boot), stringResource(R.string.receive_at_boot_desc), startAtBoot, {
                        startAtBoot = it; hub.startAtBoot = it
                    }, Icons.Filled.PowerSettingsNew)
                }
            }
        }
    }
    if (forwardDialog) ForwardDialog(controller) { forwardDialog = false }
}

/** On/off for the receiver, its name on the network and what it's playing right now. */
@Composable
private fun ReceiverHero(hub: ReceiverHub, running: Boolean) {
    val sender by hub.sender.collectAsState()
    val meta by hub.metadata.collectAsState()
    val artwork by hub.artwork.collectAsState()
    val color = if (running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val onColor = if (running) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Surface(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(28.dp)).clickable { if (running) hub.stop() else hub.start() },
        color = color, contentColor = onColor, shape = RoundedCornerShape(28.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LeadingIcon(Icons.Filled.Speaker, active = running)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.receive_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (!running) stringResource(R.string.receive_off) else stringResource(R.string.receive_waiting_as, hub.name),
                        style = MaterialTheme.typography.bodyMedium, color = onColor.copy(alpha = 0.8f),
                    )
                }
                Switch(checked = running, onCheckedChange = null)
            }
            if (running && sender != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Artwork(artwork, Modifier.size(56.dp), corner = 12.dp)
                    Column(Modifier.weight(1f)) {
                        Text(meta.title ?: sender!!, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(meta.artist, sender).joinToString(" · "), style = MaterialTheme.typography.bodyMedium,
                            color = onColor.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun ForwardDialog(controller: AppController, onDismiss: () -> Unit) {
    val servers by controller.servers.collectAsState()
    val selected = remember { mutableStateListOf<Server>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Filled.CallSplit, null) },
        title = { Text(stringResource(R.string.receive_forward)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.receive_forward_desc), style = MaterialTheme.typography.bodyMedium)
                servers.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { if (s in selected) selected.remove(s) else selected.add(s) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = s in selected, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                        Text("${s.name} · ${platformLabel(s.platform)}")
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
