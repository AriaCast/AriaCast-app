package com.aria.ariacast.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import com.aria.ariacast.R
import com.aria.ariacast.ui.SwitchRow
import com.aria.ariacast.ui.qrBitmap
import com.aria.ariacast.webremote.WebRemote

/** Turns the web remote on/off and shows its private link as text and QR code. */
@Composable
fun WebRemoteDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(WebRemote.isEnabled(context)) }
    var version by remember { mutableIntStateOf(0) }
    val link = remember(enabled, version) { if (enabled) WebRemote.link(context) else null }
    val qr = remember(link) { link?.let { qrBitmap(it, 600) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.web_remote)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.web_remote_desc), style = MaterialTheme.typography.bodyMedium)
                SwitchRow(stringResource(R.string.web_remote_on), null, enabled, { enabled = it; WebRemote.setEnabled(context, it) })
                if (link != null) {
                    qr?.let { Image(it.asImageBitmap(), link, Modifier.size(200.dp).background(Color.White).padding(8.dp)) }
                    SelectionContainer { Text(link, style = MaterialTheme.typography.bodySmall) }
                    Text(stringResource(R.string.web_remote_private), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { WebRemote.resetToken(context); version++ }) { Text(stringResource(R.string.web_remote_new_link)) }
                }
            }
        },
        confirmButton = {
            if (link != null) TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AriaCast web remote", link))
                Toast.makeText(context, context.getString(R.string.web_remote_copied), Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.copy)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
