package com.aria.ariacast

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SouthWest
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.aria.ariacast.ui.EmptyState
import com.aria.ariacast.ui.ListRow
import com.aria.ariacast.ui.ScreenList
import com.aria.ariacast.ui.SubScreenScaffold
import com.aria.ariacast.ui.groupItemShape
import com.aria.ariacast.ui.theme.AriaTheme
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample

/** Live log of what AriaCast sends and receives, for diagnosing a connection. */
class PacketLogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AriaTheme { PacketLogScreen(onBack = ::finish) } }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun PacketLogScreen(onBack: () -> Unit) {
    // PacketLogger merges repeats by mutating the last entry, so snapshot copies.
    fun snapshot() = PacketLogger.logs.map { it.copy() }
    var logs by remember { mutableStateOf(snapshot()) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { PacketLogger.logFlow.sample(250).collect { logs = snapshot() } }
    val shown = remember(logs, query) {
        if (query.isBlank()) logs else logs.filter {
            it.message.contains(query, true) || it.type.name.contains(query, true) || it.direction.name.contains(query, true)
        }
    }

    val context = LocalContext.current
    var opened by remember { mutableStateOf<PacketLog?>(null) }

    SubScreenScaffold(
        title = stringResource(R.string.packet_exchange), onBack = onBack,
        actions = {
            if (shown.isNotEmpty()) IconButton(onClick = { shareLog(context, shown) }) {
                Icon(Icons.Filled.Share, stringResource(R.string.share_log))
            }
        },
        floatingActionButton = {
            if (logs.isNotEmpty()) ExtendedFloatingActionButton(
                onClick = { PacketLogger.clear(); logs = emptyList() },
                icon = { Icon(Icons.Filled.DeleteSweep, null) },
                text = { Text(stringResource(R.string.clear_logs)) },
            )
        },
    ) { padding ->
        ScreenList(Modifier.padding(padding)) {
            item {
                OutlinedTextField(
                    query, { query = it },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    placeholder = { Text(stringResource(R.string.search_logs)) },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, stringResource(R.string.cancel)) } },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedBorderColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                )
            }
            if (shown.isEmpty()) {
                item { EmptyState(Icons.AutoMirrored.Filled.ReceiptLong, stringResource(R.string.packet_exchange), stringResource(R.string.packet_log_empty)) }
            }
            itemsIndexed(shown, key = { i, l -> "$i-${l.timestamp}-${l.message.hashCode()}" }) { i, log ->
                val incoming = log.direction == PacketDirection.IN
                ListRow(
                    headline = log.message,
                    supporting = buildString {
                        append(log.timestamp); append(" · "); append(log.type.name)
                        if (log.count > 1) append(" · ×${log.count}")
                    },
                    leading = {
                        Icon(
                            if (incoming) Icons.Filled.SouthWest else Icons.Filled.NorthEast, log.direction.name,
                            tint = if (incoming) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                        )
                    },
                    shape = groupItemShape(i, shown.size),
                    modifier = Modifier.padding(bottom = 2.dp),
                    // Long lines are cut off in the list: tap for the whole line.
                    onClick = { opened = log },
                )
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    opened?.let { log ->
        AlertDialog(
            onDismissRequest = { opened = null },
            title = { Text("${log.timestamp} · ${log.direction.name} · ${log.type.name}") },
            text = { SelectionContainer { Text(log.message, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } },
            confirmButton = { TextButton(onClick = { opened = null }) { Text(stringResource(R.string.close)) } },
            dismissButton = {
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("AriaCast log", log.message))
                }) { Text(stringResource(R.string.copy)) }
            },
        )
    }
}

/** Plain text, oldest first, so it reads like a log when pasted into an issue. */
internal fun formatLog(logs: List<PacketLog>): String = logs.asReversed().joinToString("\n") { l ->
    "${l.timestamp} ${l.direction.name} ${l.type.name}" + (if (l.count > 1) " x${l.count}" else "") + " ${l.message}"
}

private fun shareLog(context: Context, logs: List<PacketLog>) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "AriaCast packet log")
        putExtra(Intent.EXTRA_TEXT, formatLog(logs))
    }
    context.startActivity(Intent.createChooser(send, null))
}
