package com.aria.ariacast.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.aria.ariacast.R
import com.aria.ariacast.receiver.ReceiverHub
import com.aria.ariacast.sendspin.SendspinHub
import com.aria.ariacast.ui.SwitchRow
import com.aria.ariacast.ui.qrBitmap

/** Sendspin (Music Assistant) part of the Receive card. */
@Composable
fun SendspinSection() {
    val context = LocalContext.current
    val hub = remember { SendspinHub.get(context) }
    val receiver = remember { ReceiverHub.get(context) }
    val status by hub.status.collectAsState()
    var enabled by remember { mutableStateOf(hub.enabled) }
    var unpaired by remember { mutableStateOf(hub.identity.unpairedAccess) }
    var pairDialog by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }

    HorizontalDivider()
    SwitchRow(stringResource(R.string.sendspin_title), stringResource(R.string.sendspin_desc), enabled, {
        enabled = it; hub.enabled = it
        if (it) hub.start(receiver.name) else hub.stop()
    })
    if (!enabled) return
    Text(
        when {
            status.serverName == null -> stringResource(R.string.sendspin_waiting)
            status.sourceStreaming -> stringResource(R.string.sendspin_source_streaming, status.serverName!!)
            status.playing -> stringResource(R.string.sendspin_playing, status.serverName!!)
            else -> stringResource(if (status.paired) R.string.sendspin_connected_paired else R.string.sendspin_connected_unpaired, status.serverName!!)
        },
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = { pairDialog = true }) { Text(stringResource(R.string.sendspin_pair)) }
    SwitchRow(stringResource(R.string.sendspin_unpaired), stringResource(R.string.sendspin_unpaired_desc), unpaired, {
        unpaired = it; hub.identity.unpairedAccess = it
    })
    val paired = remember(refresh, status) { hub.identity.pairedServers() }
    if (paired.isNotEmpty()) {
        Text(stringResource(R.string.sendspin_paired_servers), style = MaterialTheme.typography.labelLarge)
        paired.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, Modifier.weight(1f))
                IconButton(onClick = { hub.forgetServer(p.serverId); refresh++ }) { Icon(Icons.Filled.Delete, stringResource(R.string.sendspin_forget)) }
            }
        }
    }
    if (pairDialog) PairDialog(hub.identity.pairingToken) { pairDialog = false }
}

@Composable
private fun PairDialog(token: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val qr = remember(token) { qrBitmap(token, 600) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sendspin_pair)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.sendspin_pair_desc), style = MaterialTheme.typography.bodyMedium)
                qr?.let { Image(it.asImageBitmap(), stringResource(R.string.sendspin_pair), Modifier.size(220.dp).background(Color.White).padding(8.dp)) }
                SelectionContainer { Text(token, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.sendspin_pair_secret), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Sendspin pairing token", token))
                Toast.makeText(context, context.getString(R.string.sendspin_copied), Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.copy)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
