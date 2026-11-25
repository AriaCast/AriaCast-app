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
import com.aria.ariacast.sendspin.SendspinHub
import com.aria.ariacast.ui.LeadingIcon
import com.aria.ariacast.ui.ListGroup
import com.aria.ariacast.ui.ListRow
import com.aria.ariacast.ui.SwitchListRow
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Input
import com.aria.ariacast.ui.qrBitmap

/**
 * Sendspin (Music Assistant) settings, under Settings > Protocols. Music Assistant plays
 * here while Receive is on; the switch only adds the Live input (source@v1).
 */
@Composable
fun SendspinSection() {
    val context = LocalContext.current
    val hub = remember { SendspinHub.get(context) }
    val status by hub.status.collectAsState()
    var liveInput by remember { mutableStateOf(hub.liveInputEnabled) }
    var unpaired by remember { mutableStateOf(hub.identity.unpairedAccess) }
    var pairDialog by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }

    ListGroup(title = stringResource(R.string.sendspin_title)) {
        ListRow(
            headline = when {
                !status.running -> stringResource(R.string.sendspin_inactive)
                status.serverName == null -> stringResource(R.string.sendspin_waiting)
                else -> stringResource(if (status.paired) R.string.sendspin_connected_paired else R.string.sendspin_connected_unpaired, status.serverName!!)
            },
            supporting = if (!status.running) stringResource(R.string.sendspin_inactive_desc) else null,
            leading = { LeadingIcon(Icons.Filled.Link, active = status.serverName != null) },
        )
        SwitchListRow(stringResource(R.string.sendspin_live_input), stringResource(R.string.sendspin_live_input_desc), liveInput, {
            liveInput = it; hub.liveInputEnabled = it
        }, Icons.Filled.Input)
        // Its own row: a trailing button this long squeezed the status text unreadably.
        ListRow(
            headline = stringResource(R.string.sendspin_pair),
            supporting = stringResource(R.string.sendspin_pair_row_desc),
            leading = { LeadingIcon(Icons.Filled.QrCode) },
            onClick = { pairDialog = true },
        )
        SwitchListRow(stringResource(R.string.sendspin_unpaired), stringResource(R.string.sendspin_unpaired_desc), unpaired, {
            unpaired = it; hub.identity.unpairedAccess = it
        }, Icons.Filled.LockOpen)
        val paired = remember(refresh, status) { hub.identity.pairedServers() }
        paired.forEach { p ->
            ListRow(
                headline = p.name,
                supporting = stringResource(R.string.sendspin_paired_servers),
                leading = { LeadingIcon(Icons.Filled.Lock) },
                trailing = { IconButton(onClick = { hub.forgetServer(p.serverId); refresh++ }) { Icon(Icons.Filled.Delete, stringResource(R.string.sendspin_forget)) } },
            )
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
