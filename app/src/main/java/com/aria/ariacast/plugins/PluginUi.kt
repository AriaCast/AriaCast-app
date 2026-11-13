package com.aria.ariacast.plugins

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plugin API v2 user interface (docs/ROADMAP-2.0.md, "Plugin API v2").
 *
 * Instead of inflating XML layouts (removed in 2.0), a plugin describes its UI as a small
 * tree and calls `ui.render(tree)`; the app draws it with Compose. Interactive nodes have an
 * `id`, and user actions come back through `ui.onEvent(function(id, value) { ... })`.
 *
 * Node types: column, row, header, text, button, switch, slider, progress, stat, image,
 * input, divider. See [PluginNode.parse] for the accepted properties.
 */
data class PluginNode(
    val type: String,
    val id: String? = null,
    val text: String? = null,
    val label: String? = null,
    val value: Double? = null,
    val min: Double = 0.0,
    val max: Double = 1.0,
    val step: Double? = null,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val url: String? = null,
    val hint: String? = null,
    val style: String? = null,
    val children: List<PluginNode> = emptyList(),
) {
    companion object {
        val TYPES = setOf("column", "row", "header", "text", "button", "switch", "slider", "progress", "stat", "image", "input", "divider")
        private const val MAX_NODES = 200
        private const val MAX_DEPTH = 8

        /** Parses a node tree from JSON. Unknown types and oversized trees are rejected. */
        fun parse(json: String): PluginNode = parse(JSONObject(json), intArrayOf(0), 0)

        private fun parse(o: JSONObject, count: IntArray, depth: Int): PluginNode {
            require(depth <= MAX_DEPTH) { "plugin UI nested too deeply" }
            require(++count[0] <= MAX_NODES) { "plugin UI has too many nodes" }
            val type = o.optString("type", "column")
            require(type in TYPES) { "unknown plugin UI node type '$type'" }
            val kids = o.optJSONArray("children") ?: JSONArray()
            return PluginNode(
                type = type,
                id = o.optString("id").ifEmpty { null },
                text = o.optString("text").ifEmpty { null },
                label = o.optString("label").ifEmpty { null },
                value = if (o.has("value")) o.optDouble("value").takeIf { !it.isNaN() } else null,
                min = o.optDouble("min", 0.0),
                max = o.optDouble("max", 1.0),
                step = if (o.has("step")) o.optDouble("step") else null,
                checked = o.optBoolean("checked", false),
                enabled = o.optBoolean("enabled", true),
                url = o.optString("url").ifEmpty { null }?.takeIf { it.startsWith("http://") || it.startsWith("https://") },
                hint = o.optString("hint").ifEmpty { null },
                style = o.optString("style").ifEmpty { null },
                children = (0 until kids.length()).map { parse(kids.getJSONObject(it), count, depth + 1) },
            )
        }
    }
}

data class PluginPanel(val pluginId: String, val title: String, val root: PluginNode)

/** Shared between the plugin runtime (writes) and the Compose UI (reads). */
object PluginUiRegistry {
    private val _panels = MutableStateFlow<Map<String, PluginPanel>>(emptyMap())
    val panels: StateFlow<Map<String, PluginPanel>> = _panels.asStateFlow()

    private val handlers = java.util.concurrent.ConcurrentHashMap<String, (String, Any?) -> Unit>()

    fun render(pluginId: String, title: String, root: PluginNode) =
        _panels.update { it + (pluginId to PluginPanel(pluginId, title, root)) }

    fun clear(pluginId: String) = _panels.update { it - pluginId }

    fun setHandler(pluginId: String, handler: (String, Any?) -> Unit) { handlers[pluginId] = handler }

    fun removePlugin(pluginId: String) {
        handlers.remove(pluginId)
        clear(pluginId)
    }

    /** Called by the UI when the user interacts with a node that has an id. */
    fun dispatch(pluginId: String, nodeId: String, value: Any?) {
        handlers[pluginId]?.invoke(nodeId, value)
    }
}
