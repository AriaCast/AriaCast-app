package com.aria.ariacast

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.ScriptableObject
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import okhttp3.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

class PluginManager(private val context: Context) {

    private val sharedPreferences = context.getSharedPreferences("plugins_prefs", Context.MODE_PRIVATE)
    private var activeService: AudioCastService? = null
    private val runningPluginIds = mutableSetOf<String>()
    
    private val internalPluginsDir: File
        get() = File(context.filesDir, "plugins")

    init {
        if (!internalPluginsDir.exists()) {
            internalPluginsDir.mkdirs()
        }
        installBuiltInPluginsInternal()
    }

    private fun installBuiltInPluginsInternal() {
        // Manual Server Plugin
        val manualJson = """
            {
              "id": "manual_server",
              "name": "Manual Server Entry",
              "description": "Manually add a server by IP and Port for cross-VLAN casting.",
              "version": "1.0.0",
              "author": "AriaCast Team",
              "scriptPath": "manual_server.js"
            }
        """.trimIndent()

        val manualScript = """
            console.info("Manual Server Plugin Loaded");

            function renderUI() {
                ui.run(function() {
                    ui.clear();
                    
                    var header = ui.inflate("item_plugin_header");
                    var ht = ui.findView(header, "headerText");
                    if (ht) ht.setText("Manual Server Entry");
                    ui.add(header);

                    var ipInput = ui.inflate("item_plugin_input");
                    var ipEdit = ui.findView(ipInput, "editText");
                    if (ipEdit) {
                        ipEdit.setHint("IP Address (e.g. 192.168.1.50)");
                        ipEdit.setText(storage.get("last_manual_ip") || "");
                    }
                    ui.add(ipInput);

                    var portInput = ui.inflate("item_plugin_input");
                    var portEdit = ui.findView(portInput, "editText");
                    if (portEdit) {
                        portEdit.setHint("Port (Default: 12889)");
                        portEdit.setInputType(2); // TYPE_CLASS_NUMBER
                        portEdit.setText(storage.get("last_manual_port") || "12889");
                    }
                    ui.add(portInput);

                    var btn = ui.inflate("item_plugin_button");
                    var bt = ui.findView(btn, "buttonText");
                    if (bt) bt.setText("Add Server");
                    btn.setOnClickListener(function() {
                        var ip = ipEdit.getText().toString().trim();
                        var portStr = portEdit.getText().toString().trim();
                        var port = parseInt(portStr) || 12889;
                        
                        if (ip) {
                            if (discovery) {
                                var success = discovery.addManualServer(ip, port, "Manual: " + ip);
                                if (success) {
                                    storage.set("last_manual_ip", ip);
                                    storage.set("last_manual_port", String(port));
                                    android.widget.Toast.makeText(activity, "Server added to list", 0).show();
                                } else {
                                    android.widget.Toast.makeText(activity, "Invalid IP Address", 0).show();
                                }
                            }
                        }
                    });
                    ui.add(btn);
                });
            }

            renderUI();
        """.trimIndent()

        // MusicAssistant Plugin
        val maJson = """
            {
              "id": "music_assistant",
              "name": "MusicAssistant Control",
              "description": "Native controls and output selection for MusicAssistant servers.",
              "version": "1.9.7",
              "author": "AriaCast Team",
              "scriptPath": "music_assistant.js"
            }
        """.trimIndent()

        val maScript = """
            console.info("MA Plugin Loaded");
            var currentHost = null;
            var isPolling = false;

            function startPolling(host) {
                if (isPolling) return;
                isPolling = true;
                bg.run(function() {
                    while(currentHost === host) {
                        try { renderMAUI(host); } catch(e) {}
                        java.lang.Thread.sleep(10000); 
                    }
                    isPolling = false;
                });
            }

            events.onServiceConnected(function(s) {
                if (s) storage.set("last_host", s.serverHost || "");
            });

            events.onStateChanged(function(state) {
                if (!service) { ui.clear(); currentHost = null; return; }
                var name = String(service.serverName || "");
                var host = String(service.serverHost || "");
                var isMA = name.toLowerCase().indexOf("musicassistant") !== -1 || name.toLowerCase().indexOf("manual") !== -1;

                if (isMA && (state === "CONNECTING" || state === "CASTING")) {
                    if (host !== currentHost) { currentHost = host; startPolling(host); }
                    renderMAUI(host);
                } else { ui.clear(); currentHost = null; }
            });

            function renderMAUI(host) {
                if (host !== currentHost) return; 
                var token = storage.get("auth_token");
                var baseUrl = "http://" + host + ":8095";
                var playersJson = null;
                if (token) playersJson = ws.request("ws://" + host + ":8095/ws", "players/all", null, token);
                if (!playersJson || String(playersJson).indexOf("Error") === 0) {
                    var t = String(token || "").replace("Bearer ", "").replace(/"/g, "").trim();
                    playersJson = http.post(baseUrl + "/api", JSON.stringify({ command: "players/all", args: {} }), t ? JSON.stringify({ "Authorization": "Bearer " + t }) : null);
                }

                ui.run(function() {
                    if (host !== currentHost) return;
                    ui.clear();
                    var header = ui.inflate("item_plugin_header");
                    var ht = ui.findView(header, "headerText");
                    if (ht) ht.setText("Music Assistant Players");
                    ui.add(header);

                    if (playersJson && String(playersJson).indexOf("Error") !== 0) {
                        try {
                            var players = JSON.parse(playersJson);
                            var list = Array.isArray(players) ? players : (players.results || []);
                            list.forEach(function(p) {
                                var itemView = ui.inflate("item_server");
                                var nameTxt = ui.findView(itemView, "serverName");
                                if (nameTxt) nameTxt.setText(p.name || p.display_name || "Unknown");
                                itemView.setOnClickListener(function() {
                                    bg.run(function() {
                                        var t = String(storage.get("auth_token") || "").replace("Bearer ", "").replace(/"/g, "").trim();
                                        http.post(baseUrl + "/api", JSON.stringify({ command: "players/cmd/select", args: { player_id: p.player_id || p.id } }), t ? JSON.stringify({ "Authorization": "Bearer " + t }) : null);
                                    });
                                });
                                ui.add(itemView);
                            });
                        } catch(e) {}
                    }
                });
            }
        """.trimIndent()

        // Advanced Visualizer Plugin
        val vizJson = """
            {
              "id": "visualizer",
              "name": "Live Waveform",
              "description": "High-performance real-time audio waveform visualizer.",
              "version": "1.1.2",
              "author": "AriaCast Team",
              "scriptPath": "visualizer.js"
            }
        """.trimIndent()

        val vizScript = """
            console.info("Advanced Visualizer Loaded");
            var vizView = null;

            events.onStateChanged(function(state) {
                if (state === "CASTING") {
                    ui.run(function() {
                        ui.clear();
                        var header = ui.inflate("item_plugin_header");
                        var ht = ui.findView(header, "headerText");
                        if (ht) ht.setText("Live Audio Waveform");
                        ui.add(header);
                        
                        // Use the native VisualizerView for better performance
                        vizView = new com.aria.ariacast.VisualizerView(activity);
                        vizView.setLayoutParams(new android.widget.LinearLayout.LayoutParams(-1, 300));
                        
                        var typedValue = new android.util.TypedValue();
                        activity.getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true);
                        vizView.setAccentColor(typedValue.data);
                        
                        ui.add(vizView);
                    });
                } else {
                    ui.clear();
                    vizView = null;
                }
            });

            events.onAudioBuffer(function(bytes) {
                if (vizView) {
                    vizView.updateVisualizer(bytes);
                }
            });
        """.trimIndent()

        try {
            File(internalPluginsDir, "manual_server.json").writeText(manualJson)
            File(internalPluginsDir, "manual_server.js").writeText(manualScript)
            File(internalPluginsDir, "music_assistant.json").writeText(maJson)
            File(internalPluginsDir, "music_assistant.js").writeText(maScript)
            File(internalPluginsDir, "visualizer.json").writeText(vizJson)
            File(internalPluginsDir, "visualizer.js").writeText(vizScript)
        } catch (e: Exception) {
            Log.e("PluginManager", "Failed to install built-in plugins", e)
        }
    }

    fun getPlugins(): List<Plugin> {
        val plugins = mutableListOf<Plugin>()
        scanDirForPlugins(internalPluginsDir, plugins)
        val customPathUri = sharedPreferences.getString("plugin_folder_uri", null)
        if (!customPathUri.isNullOrEmpty()) {
            try {
                val uri = Uri.parse(customPathUri)
                val documentDir = DocumentFile.fromTreeUri(context, uri)
                if (documentDir != null && documentDir.exists()) {
                    scanDocumentDirForPlugins(documentDir, plugins)
                }
            } catch (e: Exception) {}
        }
        return plugins
    }

    private fun scanDirForPlugins(dir: File, plugins: MutableList<Plugin>) {
        val files = dir.listFiles { _, name -> name.endsWith(".json") }
        files?.forEach { file ->
            try {
                val plugin = Plugin.fromJson(file.readText())
                if (plugins.none { it.id == plugin.id }) {
                    plugin.isEnabled = sharedPreferences.getBoolean(plugin.id, false)
                    plugins.add(plugin)
                }
            } catch (e: Exception) {}
        }
    }

    private fun scanDocumentDirForPlugins(dir: DocumentFile, plugins: MutableList<Plugin>) {
        val files = dir.listFiles()
        files.filter { it.name?.endsWith(".json") == true }.forEach { file ->
            try {
                context.contentResolver.openInputStream(file.uri)?.use { inputStream ->
                    val json = inputStream.bufferedReader().readText()
                    val plugin = Plugin.fromJson(json)
                    if (plugins.none { it.id == plugin.id }) {
                        plugin.isEnabled = sharedPreferences.getBoolean(plugin.id, false)
                        plugins.add(plugin)
                    }
                }
            } catch (e: Exception) {}
        }
    }

    fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        sharedPreferences.edit().putBoolean(pluginId, enabled).apply()
    }

    fun runEnabledPlugins(activity: MainActivity, service: AudioCastService?) {
        activeService = service
        getPlugins().filter { it.isEnabled }.forEach { plugin ->
            if (!runningPluginIds.contains(plugin.id)) {
                runningPluginIds.add(plugin.id)
                runPlugin(plugin, activity, service)
            }
        }
    }
    
    fun requestPluginConfig(pluginId: String, activity: android.app.Activity) {
        val plugin = getPlugins().find { it.id == pluginId } ?: return
        runPlugin(plugin, activity, null, isConfigOnly = true)
    }

    private fun runPlugin(plugin: Plugin, activity: android.app.Activity, initialService: AudioCastService?, isConfigOnly: Boolean = false) {
        var scriptContent: String? = null
        val internalScriptFile = File(internalPluginsDir, plugin.scriptPath)
        if (internalScriptFile.exists()) scriptContent = internalScriptFile.readText()
        if (scriptContent == null) {
            val customPathUri = sharedPreferences.getString("plugin_folder_uri", null)
            if (!customPathUri.isNullOrEmpty()) {
                try {
                    val uri = Uri.parse(customPathUri)
                    val documentDir = DocumentFile.fromTreeUri(context, uri)
                    val scriptFile = documentDir?.findFile(plugin.scriptPath)
                    if (scriptFile != null && scriptFile.exists()) {
                        context.contentResolver.openInputStream(scriptFile.uri)?.use { inputStream ->
                            scriptContent = inputStream.bufferedReader().readText()
                        }
                    }
                } catch (e: Exception) {}
            }
        }

        if (scriptContent == null) {
            runningPluginIds.remove(plugin.id)
            return
        }

        val finalScriptContent = scriptContent!!
        Thread {
            val rhino = RhinoContext.enter()
            rhino.optimizationLevel = -1
            try {
                val scope = rhino.initStandardObjects()
                ScriptableObject.putProperty(scope, "activity", RhinoContext.javaToJS(activity, scope))
                ScriptableObject.putProperty(scope, "context", RhinoContext.javaToJS(context, scope))
                ScriptableObject.putProperty(scope, "service", RhinoContext.javaToJS(initialService, scope))
                
                if (activity is MainActivity) {
                    ScriptableObject.putProperty(scope, "discovery", RhinoContext.javaToJS(activity.discoveryManager, scope))
                }

                val uiHelper = object {
                    fun run(f: Runnable) = activity.runOnUiThread {
                        val cx = RhinoContext.enter()
                        try { cx.optimizationLevel = -1; f.run() } finally { RhinoContext.exit() }
                    }
                    fun clear() = activity.runOnUiThread { 
                        if (activity is MainActivity) {
                            val container = getPluginSubContainer(activity, plugin.id)
                            container.removeAllViews()
                            container.visibility = View.GONE
                            updateMainPluginContainerVisibility(activity)
                        }
                    }
                    fun inflate(layoutName: String): View? {
                        val id = activity.resources.getIdentifier(layoutName, "layout", activity.packageName)
                        if (id != 0 && activity is MainActivity) {
                            return activity.layoutInflater.inflate(id, activity.pluginContainer, false)
                        }
                        return null
                    }
                    fun findView(parent: View, name: String): View? {
                        val id = activity.resources.getIdentifier(name, "id", activity.packageName)
                        return if (id != 0) parent.findViewById(id) else null
                    }
                    fun add(view: View) = activity.runOnUiThread {
                        if (activity is MainActivity) {
                            val container = getPluginSubContainer(activity, plugin.id)
                            container.addView(view)
                            container.visibility = View.VISIBLE
                            activity.pluginContainer.visibility = View.VISIBLE
                        }
                    }
                }
                ScriptableObject.putProperty(scope, "ui", RhinoContext.javaToJS(uiHelper, scope))

                val bgHelper = object {
                    fun run(f: Runnable) {
                        Thread {
                            val cx = RhinoContext.enter()
                            try { cx.optimizationLevel = -1; f.run() } 
                            catch (e: Exception) { Log.e("PluginBG", "Error", e) } 
                            finally { RhinoContext.exit() }
                        }.start()
                    }
                }
                ScriptableObject.putProperty(scope, "bg", RhinoContext.javaToJS(bgHelper, scope))

                val storageHelper = object {
                    fun get(key: String): String? = sharedPreferences.getString("plugin_${plugin.id}_$key", null)
                    fun set(key: String, value: String) = sharedPreferences.edit().putString("plugin_${plugin.id}_$key", value).apply()
                }
                ScriptableObject.putProperty(scope, "storage", RhinoContext.javaToJS(storageHelper, scope))

                val events = object {
                    fun onServiceConnected(callback: org.mozilla.javascript.Function) {
                        if (isConfigOnly || activity !is MainActivity) return
                        activity.lifecycleScope.launch(Dispatchers.IO) {
                            activity.audioCastServiceFlow.collectLatest { s ->
                                activeService = s
                                if (s != null) {
                                    val cx = RhinoContext.enter()
                                    try {
                                        cx.optimizationLevel = -1
                                        ScriptableObject.putProperty(scope, "service", RhinoContext.javaToJS(s, scope))
                                        callback.call(cx, scope, scope, arrayOf(RhinoContext.javaToJS(s, scope)))
                                    } finally { RhinoContext.exit() }
                                } else { activity.runOnUiThread { uiHelper.clear() } }
                            }
                        }
                    }

                    fun onStateChanged(callback: org.mozilla.javascript.Function) {
                        if (isConfigOnly || activity !is MainActivity) return
                        activity.lifecycleScope.launch(Dispatchers.IO) {
                            activity.audioCastServiceFlow.filterNotNull().collectLatest { s ->
                                s.state.collectLatest { state ->
                                    val cx = RhinoContext.enter()
                                    try {
                                        cx.optimizationLevel = -1
                                        callback.call(cx, scope, scope, arrayOf(state.name))
                                    } finally { RhinoContext.exit() }
                                }
                            }
                        }
                    }

                    fun onAudioBuffer(callback: org.mozilla.javascript.Function) {
                        if (isConfigOnly || activity !is MainActivity) return
                        activity.lifecycleScope.launch(Dispatchers.IO) {
                            activity.audioCastServiceFlow.filterNotNull().collectLatest { s ->
                                s.audioBufferFlow.collectLatest { buffer ->
                                    val cx = RhinoContext.enter()
                                    try {
                                        cx.optimizationLevel = -1
                                        callback.call(cx, scope, scope, arrayOf(RhinoContext.javaToJS(buffer, scope)))
                                    } finally { RhinoContext.exit() }
                                }
                            }
                        }
                    }
                    
                    fun onConfigRequested(callback: org.mozilla.javascript.Function) {
                        if (!isConfigOnly) return
                        activity.runOnUiThread {
                            val cx = RhinoContext.enter()
                            try {
                                cx.optimizationLevel = -1
                                callback.call(cx, scope, scope, arrayOf())
                            } finally { RhinoContext.exit() }
                        }
                    }
                }
                ScriptableObject.putProperty(scope, "events", RhinoContext.javaToJS(events, scope))

                val wsHelper = object {
                    fun request(url: Any?, command: Any?, argsJson: Any?, token: Any?): String? {
                        val urlStr = url?.toString() ?: return "Error: Null URL"
                        val cmdStr = command?.toString() ?: return "Error: Null Command"
                        val args = argsJson?.toString()
                        val tokenStr = token?.toString()?.replace("Bearer ", "")?.replace("\"", "")?.trim()
                        val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
                        val latch = CountDownLatch(1)
                        var result: String? = null
                        client.newWebSocket(Request.Builder().url(urlStr).build(), object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                if (!tokenStr.isNullOrEmpty() && tokenStr != "null") {
                                    webSocket.send(JSONObject().apply {
                                        put("message_id", "auth-123")
                                        put("command", "auth")
                                        put("args", JSONObject().apply { put("token", tokenStr) })
                                    }.toString())
                                } else { sendCmd(webSocket) }
                            }
                            private fun sendCmd(webSocket: WebSocket) {
                                webSocket.send(JSONObject().apply {
                                    put("command", cmdStr)
                                    put("message_id", "plugin_cmd")
                                    put("args", if (!args.isNullOrEmpty() && args != "null") JSONObject(args) else JSONObject())
                                }.toString())
                            }
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                try {
                                    val json = JSONObject(text)
                                    val msgId = json.optString("message_id")
                                    if (msgId == "auth-123") {
                                        if (json.optJSONObject("result")?.optBoolean("authenticated") == true) sendCmd(webSocket)
                                        else { result = "Error: Auth Failed"; webSocket.close(1000, "Auth Failed"); latch.countDown() }
                                    } else if (msgId == "plugin_cmd") {
                                        result = if (json.has("error_code")) "Error: " + json.optString("details") else json.opt("result")?.toString() ?: text
                                        webSocket.close(1000, "Done"); latch.countDown()
                                    }
                                } catch (e: Exception) {}
                            }
                            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { result = "Error: " + t.message; latch.countDown() }
                            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { latch.countDown() }
                        })
                        if (!latch.await(15, TimeUnit.SECONDS)) result = result ?: "Error: Timeout"
                        return result
                    }
                }
                ScriptableObject.putProperty(scope, "ws", RhinoContext.javaToJS(wsHelper, scope))

                val networkHelper = object {
                    fun post(url: Any?, body: Any? = null, headersJson: Any? = null): String? = try {
                        val urlStr = url?.toString() ?: ""
                        val bodyStr = body?.toString() ?: ""
                        val conn = URL(urlStr).openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.doOutput = true
                        conn.connectTimeout = 5000
                        conn.readTimeout = 5000
                        conn.setRequestProperty("Content-Type", "application/json")
                        headersJson?.toString()?.let { if (it != "null" && it.isNotEmpty()) { val json = JSONObject(it); json.keys().forEach { key -> conn.setRequestProperty(key, json.getString(key)) } } }
                        conn.outputStream.use { it.write(bodyStr.toByteArray()) }
                        if (conn.responseCode in 200..299) conn.inputStream.bufferedReader(Charsets.UTF_8).readText() else "Error: " + conn.responseCode
                    } catch (e: Exception) { "Error: " + e.message }
                }
                ScriptableObject.putProperty(scope, "http", RhinoContext.javaToJS(networkHelper, scope))

                val logger = object {
                    fun info(msg: String) = Log.i("Plugin:" + plugin.name, msg)
                    fun error(msg: String) = Log.e("Plugin:" + plugin.name, msg)
                }
                ScriptableObject.putProperty(scope, "console", RhinoContext.javaToJS(logger, scope))

                rhino.evaluateString(scope, finalScriptContent, plugin.name, 1, null)
            } catch (e: Exception) {
                Log.e("PluginManager", "Error executing plugin: " + plugin.name, e)
            } finally {
                RhinoContext.exit()
            }
        }.start()
    }

    private fun getPluginSubContainer(activity: MainActivity, pluginId: String): LinearLayout {
        val tag = "plugin_sub_container_$pluginId"
        var container = activity.pluginContainer.findViewWithTag<LinearLayout>(tag)
        if (container == null) {
            container = LinearLayout(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.VERTICAL
                this.tag = tag
                visibility = View.GONE
            }
            activity.pluginContainer.addView(container)
        }
        return container
    }

    private fun updateMainPluginContainerVisibility(activity: MainActivity) {
        var anyVisible = false
        for (i in 0 until activity.pluginContainer.childCount) {
            if (activity.pluginContainer.getChildAt(i).visibility == View.VISIBLE) {
                anyVisible = true
                break
            }
        }
        activity.pluginContainer.visibility = if (anyVisible) View.VISIBLE else View.GONE
    }
}
