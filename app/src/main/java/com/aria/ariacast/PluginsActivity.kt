package com.aria.ariacast

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aria.ariacast.ui.EmptyState
import com.aria.ariacast.ui.LeadingIcon
import com.aria.ariacast.ui.ListBlock
import com.aria.ariacast.ui.ListGroup
import com.aria.ariacast.ui.ListRow
import com.aria.ariacast.ui.ScreenList
import com.aria.ariacast.ui.SubScreenScaffold
import com.aria.ariacast.ui.theme.AriaTheme
import kotlinx.coroutines.launch

/** JavaScript plugins: consent, plugin folder, sync from GitHub, enable/configure. */
class PluginsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AriaTheme { PluginsScreen(this, onBack = ::finish) } }
    }
}

private const val PREFS = "plugins_prefs"
private const val KEY_CONSENT = "plugin_consent_given"
private const val KEY_FOLDER = "plugin_folder_uri"

@Composable
private fun PluginsScreen(activity: ComponentActivity, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var consent by remember { mutableStateOf(prefs.getBoolean(KEY_CONSENT, false)) }
    var hasFolder by remember { mutableStateOf(prefs.getString(KEY_FOLDER, null) != null) }
    var manager by remember { mutableStateOf<PluginManager?>(null) }
    var plugins by remember { mutableStateOf<List<Plugin>>(emptyList()) }
    var syncing by remember { mutableStateOf(false) }
    var disabledAfterUpdate by remember { mutableStateOf<List<String>>(emptyList()) }
    var details by remember { mutableStateOf<Plugin?>(null) }

    fun sync() {
        val m = manager ?: return
        scope.launch {
            syncing = true
            val result = m.syncPluginsFromGitHub()
            syncing = false
            plugins = m.getPlugins()
            disabledAfterUpdate = result.disabledPluginNames
            snackbar.showSnackbar(context.getString(if (result.success) R.string.plugins_synced else R.string.sync_failed))
        }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            prefs.edit().putString(KEY_FOLDER, uri.toString()).apply()
            hasFolder = true
            manager = PluginManager(context)
            sync()
        }
    }

    LaunchedEffect(consent, hasFolder) {
        if (consent && hasFolder && manager == null) {
            manager = PluginManager(context)
            plugins = manager!!.getPlugins()
            sync()
        }
    }

    SubScreenScaffold(
        title = stringResource(R.string.plugins), onBack = onBack,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (consent && hasFolder) ExtendedFloatingActionButton(
                onClick = { if (!syncing) sync() },
                icon = { Icon(Icons.Filled.Sync, null) },
                text = { Text(stringResource(R.string.plugins_sync)) },
            )
        },
    ) { padding ->
        ScreenList(Modifier.padding(padding)) {
            if (syncing) item { LinearProgressIndicator(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            when {
                !consent -> {}
                !hasFolder -> item {
                    EmptyState(
                        Icons.Filled.Folder, stringResource(R.string.plugin_folder_title), stringResource(R.string.plugin_folder_message), Modifier.padding(top = 8.dp),
                        action = { TextButton(onClick = { pickFolder.launch(null) }) { Text(stringResource(R.string.select_folder)) } },
                    )
                }
                plugins.isEmpty() && !syncing -> item { EmptyState(Icons.Filled.Extension, stringResource(R.string.plugins), stringResource(R.string.plugins_empty), Modifier.padding(top = 8.dp)) }
                else -> item {
                    ListGroup {
                        plugins.forEach { plugin ->
                            var enabled by remember(plugin.id) { mutableStateOf(plugin.isEnabled) }
                            ListRow(
                                headline = plugin.name,
                                supporting = stringResource(R.string.plugin_author_format, plugin.author, plugin.version),
                                leading = { LeadingIcon(Icons.Filled.Extension, active = enabled) },
                                trailing = {
                                    Switch(checked = enabled, onCheckedChange = {
                                        enabled = it; manager?.setPluginEnabled(plugin.id, it)
                                    })
                                },
                                onClick = { details = plugin },
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
    }

    if (!consent) {
        AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.Filled.Warning, null) },
            title = { Text(stringResource(R.string.plugin_consent_title)) },
            text = { Text(stringResource(R.string.plugin_consent_message), Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { prefs.edit().putBoolean(KEY_CONSENT, true).apply(); consent = true }) { Text(stringResource(R.string.accept)) } },
            dismissButton = { TextButton(onClick = onBack) { Text(stringResource(R.string.decline)) } },
        )
    }
    if (disabledAfterUpdate.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { disabledAfterUpdate = emptyList() },
            title = { Text(stringResource(R.string.plugins_updated_disabled_title)) },
            text = { Text(stringResource(R.string.plugins_updated_disabled_message, disabledAfterUpdate.joinToString(", "))) },
            confirmButton = { TextButton(onClick = { disabledAfterUpdate = emptyList() }) { Text(stringResource(R.string.ok)) } },
        )
    }
    details?.let { p ->
        AlertDialog(
            onDismissRequest = { details = null },
            icon = { Icon(Icons.Filled.Extension, null) },
            title = { Text(p.name) },
            text = {
                ListGroup {
                    ListBlock { Text(p.description) }
                    ListBlock { Text(stringResource(R.string.plugin_author_format, p.author, p.version)) }
                }
            },
            confirmButton = { TextButton(onClick = { details = null; manager?.requestPluginConfig(p.id, activity) }) { Text(stringResource(R.string.configure)) } },
            dismissButton = { TextButton(onClick = { details = null }) { Text(stringResource(R.string.close)) } },
        )
    }
}
