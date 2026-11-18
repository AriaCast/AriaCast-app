package com.aria.ariacast

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment
import androidx.core.content.FileProvider
import com.aria.ariacast.sendspin.SendspinHub
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
import androidx.compose.material3.FilledTonalButton
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
    var exporting by remember { mutableStateOf(false) }

    SubScreenScaffold(
        title = stringResource(R.string.packet_exchange), onBack = onBack,
        actions = {
            if (logs.isNotEmpty()) IconButton(onClick = { exporting = true }) {
                Icon(Icons.Filled.Share, stringResource(R.string.export_log))
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
            // A visible button as well as the top-bar icon, which testers missed.
            if (logs.isNotEmpty()) item {
                FilledTonalButton(onClick = { exporting = true }, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Icon(Icons.Filled.Share, null, Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.export_log))
                }
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

    if (exporting) ExportDialog(onDismiss = { exporting = false })

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
@Composable
private fun ExportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var scope by remember { mutableStateOf(PacketLogExport.Scope.LAST_SESSION) }
    var copied by remember { mutableStateOf(false) }
    fun report() = PacketLogExport.build(exportEnvironment(context), PacketLogger.logs.map { it.copy() }, scope,
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Share, null) },
        title = { Text(stringResource(R.string.export_log)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.export_log_desc), style = MaterialTheme.typography.bodyMedium)
                listOf(PacketLogExport.Scope.LAST_SESSION to R.string.export_scope_last, PacketLogExport.Scope.ALL to R.string.export_scope_all)
                    .forEach { (value, label) ->
                        Row(Modifier.fillMaxWidth().clickable { scope = value }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = scope == value, onClick = { scope = value })
                            Text(stringResource(label))
                        }
                    }
                if (copied) Text(stringResource(R.string.export_copied), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { shareReport(context, report()); onDismiss() }) { Text(stringResource(R.string.export_share_file)) } },
        dismissButton = {
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AriaCast report", report()))
                copied = true
            }) { Text(stringResource(R.string.export_copy_github)) }
        },
    )
}

private fun exportEnvironment(context: Context): PacketLogExport.Environment {
    val prefs = context.getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    return PacketLogExport.Environment(
        appVersion = version,
        variant = if (context.resources.getBoolean(R.bool.self_update)) "GitHub" else "F-Droid",
        android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        device = "${Build.MANUFACTURER} ${Build.MODEL}",
        protocols = listOf(
            "AirPlay" to ProtocolsActivity.KEY_AIRPLAY_ENABLED, "AirPlay 2" to ProtocolsActivity.KEY_AIRPLAY2_ENABLED,
            "Google Cast" to ProtocolsActivity.KEY_GOOGLE_CAST_ENABLED, "DLNA" to ProtocolsActivity.KEY_DLNA_ENABLED,
            "Snapcast" to ProtocolsActivity.KEY_SNAPCAST_ENABLED, "Sendspin" to SendspinHub.KEY_ENABLED,
        ).map { (name, key) -> name to prefs.getBoolean(key, false) },
    )
}

/** Shares the report as a .md file (keeps long logs intact), with a short text for apps that ignore files. */
private fun shareReport(context: Context, report: String) {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() } // only the latest export is kept
    val file = File(dir, "ariacast-log-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.md").apply { writeText(report) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/markdown"
        putExtra(Intent.EXTRA_SUBJECT, "AriaCast diagnostic report")
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null))
}
