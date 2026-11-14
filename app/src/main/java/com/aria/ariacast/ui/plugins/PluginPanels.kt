package com.aria.ariacast.ui.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aria.ariacast.plugins.PluginNode
import com.aria.ariacast.plugins.PluginUiRegistry
import com.aria.ariacast.ui.KeyValueRow
import com.aria.ariacast.ui.SectionCard

/** Every running plugin's panel (Plugin API v2), drawn from the tree it passed to ui.render(). */
@Composable
fun PluginPanels() {
    val panels by PluginUiRegistry.panels.collectAsState()
    if (panels.isEmpty()) return
    Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        panels.values.sortedBy { it.title }.forEach { panel ->
            SectionCard(title = panel.title) {
                PluginNodeView(panel.root) { id, value -> PluginUiRegistry.dispatch(panel.pluginId, id, value) }
            }
        }
    }
}

@Composable
fun PluginNodeView(node: PluginNode, onEvent: (String, Any?) -> Unit) {
    when (node.type) {
        "column" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { node.children.forEach { PluginNodeView(it, onEvent) } }
        "row" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            node.children.forEach { PluginNodeView(it, onEvent) }
        }
        "header" -> Text(node.text ?: "", style = MaterialTheme.typography.titleSmall)
        "text" -> Text(node.text ?: "", style = if (node.style == "caption") MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium)
        "button" -> {
            val click = { node.id?.let { onEvent(it, null) } ?: Unit }
            when (node.style) {
                "outlined" -> OutlinedButton(onClick = click, enabled = node.enabled) { Text(node.text ?: "") }
                "text" -> TextButton(onClick = click, enabled = node.enabled) { Text(node.text ?: "") }
                else -> Button(onClick = click, enabled = node.enabled) { Text(node.text ?: "") }
            }
        }
        "switch" -> {
            var checked by remember(node) { mutableStateOf(node.checked) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(node.label ?: node.text ?: "", Modifier.weight(1f))
                Switch(checked = checked, enabled = node.enabled, onCheckedChange = { checked = it; node.id?.let { id -> onEvent(id, it) } })
            }
        }
        "slider" -> {
            var value by remember(node) { mutableFloatStateOf((node.value ?: node.min).toFloat()) }
            val range = node.min.toFloat()..node.max.toFloat()
            val steps = node.step?.takeIf { it > 0 }?.let { ((node.max - node.min) / it).toInt() - 1 }?.coerceIn(0, 1000) ?: 0
            Column {
                node.label?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Slider(value = value, valueRange = range, steps = steps, enabled = node.enabled,
                    onValueChange = { value = it }, onValueChangeFinished = { node.id?.let { onEvent(it, value.toDouble()) } })
            }
        }
        "progress" -> {
            val v = node.value
            if (v == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { ((v - node.min) / (node.max - node.min)).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
        "stat" -> KeyValueRow(node.label ?: "", node.text ?: node.value?.toString() ?: "")
        "input" -> {
            var text by remember(node) { mutableStateOf(node.text ?: "") }
            OutlinedTextField(text, { text = it }, label = node.label?.let { { Text(it) } }, placeholder = node.hint?.let { { Text(it) } },
                singleLine = true, enabled = node.enabled, modifier = Modifier.fillMaxWidth(),
                trailingIcon = { TextButton(onClick = { node.id?.let { onEvent(it, text) } }) { Text("OK") } })
        }
        "image" -> node.url?.let { RemoteImage(it) }
        "divider" -> HorizontalDivider()
    }
}
