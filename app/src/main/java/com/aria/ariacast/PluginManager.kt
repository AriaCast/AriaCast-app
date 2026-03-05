package com.aria.ariacast

import android.content.Context
import android.util.Log
import android.view.View
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
    
    private val pluginsDir: File
        get() {
            val customPath = sharedPreferences.getString("plugin_folder", null)
            return if (!customPath.isNullOrEmpty()) {
                File(customPath)
            } else {
                File(context.filesDir, "plugins")
            }
        }

    init {
        val dir = pluginsDir
        if (!dir.exists()) {
            dir.mkdirs()
        }
        installBuiltInPlugins()
    }

    private fun installBuiltInPlugins() {
        val dir = pluginsDir
        val maJson = """
            {
              "id": "music_assistant",
              "name": "MusicAssistant Control",
              "description": "Native controls and output selection for MusicAssistant servers.",
              "version": "1.9.2",
              "author": "AriaCast Team",
              "scriptPath": "music_assistant.js"
            }
        """.trimIndent()

        val maScript = """
            console.info("MA Plugin 1.9.2 Loaded");

            var currentHost = null;
            var isPolling = false;

            function startPolling(host) {
                if (isPolling) return;
                isPolling = true;
                bg.run(function() {
                    console.info("MA: Starting polling for " + host);
                    while(currentHost === host) {
                        try {
                            renderMAUI(host);
                        } catch(e) {
                            console.error("MA: Refresh loop error: " + e);
                        }
                        java.lang.Thread.sleep(10000); 
                    }
                    isPolling = false;
                    console.info("MA: Polling stopped for " + host);
                });
            }

            events.onServiceConnected(function(s) {
                // Just update service reference, UI handled by onStateChanged
                console.info("MA: Service connected");
                if (s) {
                    storage.set("last_host", s.serverHost || "");
                }
            });

            events.onStateChanged(function(state) {
                if (!service) {
                    ui.clear();
                    currentHost = null;
                    return;
                }

                var name = String(service.serverName || "");
                var host = String(service.serverHost || "");
                var isMA = name.toLowerCase().indexOf("musicassistant") !== -1;

                console.info("MA: State changed to " + state + " for " + name);

                if (isMA && (state === "CONNECTING" || state === "CASTING")) {
                    if (host !== currentHost) {
                        currentHost = host;
                        startPolling(host);
                    }
                    renderMAUI(host);
                } else {
                    ui.clear();
                    currentHost = null;
                }
            });

            function getAuthHeaders() {
                var token = storage.get("auth_token");
                if (token) {
                    var t = String(token).replace("Bearer ", "").replace(/"/g, "").trim();
                    return JSON.stringify({ "Authorization": "Bearer " + t });
                }
                return null;
            }

            function renderMAUI(host) {
                if (host !== currentHost) return; // Prevent rendering if host changed
                
                var token = storage.get("auth_token");
                var baseUrl = "http://" + host + ":8095";
                var wsUrl = "ws://" + host + ":8095/ws";
                
                var playersJson = null;

                if (token) {
                    playersJson = ws.request(wsUrl, "players/all", null, token);
                }

                if (!playersJson || String(playersJson).indexOf("Error") === 0) {
                    var t = String(token || "").replace("Bearer ", "").replace(/"/g, "").trim();
                    var rpcBody = JSON.stringify({ command: "players/all", args: {} });
                    var headers = t ? JSON.stringify({ "Authorization": "Bearer " + t }) : null;
                    playersJson = http.post(baseUrl + "/api", rpcBody, headers);
                }

                ui.run(function() {
                    // Check again inside UI thread
                    if (host !== currentHost) return;

                    ui.clear();
                    
                    var title = new android.widget.TextView(activity);
                    title.setText("Music Assistant Players");
                    title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
                    title.setPadding(40, 30, 0, 10);
                    ui.add(title);

                    var isAuthenticated = playersJson && String(playersJson).indexOf("Error") !== 0;

                    if (!isAuthenticated) {
                        var btnLayout = new android.widget.LinearLayout(activity);
                        btnLayout.setOrientation(0);
                        btnLayout.setPadding(40, 0, 40, 0);
                        var lp = new android.widget.LinearLayout.LayoutParams(0, -2, 1);
                        
                        var loginBtn = new android.widget.Button(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
                        loginBtn.setText("Login");
                        loginBtn.setOnClickListener(function() { showLoginDialog(host); });
                        loginBtn.setLayoutParams(lp);
                        btnLayout.addView(loginBtn);

                        var tokenBtn = new android.widget.Button(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
                        tokenBtn.setText("Token");
                        tokenBtn.setOnClickListener(function() { showTokenDialog(host); });
                        tokenBtn.setLayoutParams(lp);
                        btnLayout.addView(tokenBtn);

                        ui.add(btnLayout);
                    } else {
                        var statusTxt = new android.widget.TextView(activity);
                        statusTxt.setText("Authenticated • " + host);
                        statusTxt.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
                        statusTxt.setPadding(40, 0, 0, 20);
                        statusTxt.setAlpha(0.6);
                        ui.add(statusTxt);
                    }

                    if (playersJson && String(playersJson).length > 0 && String(playersJson).indexOf("Error") !== 0) {
                        try {
                            var players = JSON.parse(playersJson);
                            var list = Array.isArray(players) ? players : (players.results || []);
                            
                            if (list.length === 0) {
                                var emptyTxt = new android.widget.TextView(activity);
                                emptyTxt.setText("No players found on server.");
                                emptyTxt.setPadding(40, 10, 0, 10);
                                ui.add(emptyTxt);
                                return;
                            }

                            var typedValue = new android.util.TypedValue();
                            activity.getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true);
                            var accentColor = typedValue.data;

                            list.forEach(function(p) {
                                var itemView = ui.inflate("item_server");
                                var nameTxt = ui.findView(itemView, "serverName");
                                var hostTxt = ui.findView(itemView, "serverHost");
                                
                                var name = p.name || p.display_name || "Unknown Player";
                                var id = p.player_id || p.id;
                                var state = p.state || (p.active ? "Active" : "Idle");

                                if (nameTxt) nameTxt.setText(name);
                                if (hostTxt) hostTxt.setText("MA Player • " + state);

                                var isPlaying = String(state).toLowerCase() === "playing";
                                if (isPlaying) {
                                    itemView.setStrokeWidth(6);
                                    itemView.setStrokeColor(android.content.res.ColorStateList.valueOf(accentColor));
                                } else {
                                    itemView.setStrokeWidth(0);
                                }

                                var isActive = p.active && (p.active_source && p.active_source.indexOf("ariacast") !== -1);
                                if (isActive) {
                                    itemView.scaleX = 1.02;
                                    itemView.scaleY = 1.02;
                                }

                                itemView.setOnClickListener(function(v) {
                                    bg.run(function() {
                                        var t = String(storage.get("auth_token") || "").replace("Bearer ", "").replace(/"/g, "").trim();
                                        var rpcUrl = baseUrl + "/api";
                                        var h = t ? JSON.stringify({ "Authorization": "Bearer " + t }) : null;
                                        
                                        var providersRes = ws.request(wsUrl, "providers", null, t);
                                        if (!providersRes || String(providersRes).indexOf("Error") === 0) {
                                            providersRes = http.post(rpcUrl, JSON.stringify({ command: "providers" }), h);
                                        }
                                        
                                        var sourceId = null;
                                        if (providersRes && String(providersRes).indexOf("Error") !== 0) {
                                            try {
                                                var providers = JSON.parse(providersRes);
                                                for (var i = 0; i < providers.length; i++) {
                                                    var prov = providers[i];
                                                    if (prov.domain === "ariacast" || (prov.name && prov.name.indexOf("AriaCast") !== -1)) {
                                                        sourceId = prov.instance_id || prov.domain;
                                                        break;
                                                    }
                                                }
                                            } catch(e) {}
                                        }
                                        
                                        if (sourceId) {
                                            var selectBody = JSON.stringify({ 
                                                command: "players/cmd/select_source", 
                                                args: { player_id: id, source: sourceId } 
                                            });
                                            http.post(rpcUrl, selectBody, h);
                                        } else {
                                            var fallbackBody = JSON.stringify({ command: "players/cmd/select", args: { player_id: id } });
                                            http.post(rpcUrl, fallbackBody, h);
                                        }
                                        
                                        java.lang.Thread.sleep(1000);
                                        renderMAUI(host);
                                    });
                                    android.widget.Toast.makeText(activity, "Targeting " + name, 0).show();
                                });

                                ui.add(itemView);
                            });
                        } catch(e) { 
                            console.error("MA: Parse error: " + e); 
                        }
                    } else {
                        var msg = new android.widget.TextView(activity);
                        var errMsg = "Check connection and token.";
                        if (!storage.get("auth_token")) errMsg = "Authentication required.";
                        else if (String(playersJson).indexOf("Error") === 0) errMsg = playersJson;
                        
                        msg.setText(errMsg);
                        msg.setPadding(40, 10, 0, 10);
                        ui.add(msg);
                    }
                });
            }

            function showLoginDialog(host) {
                var builder = new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity);
                builder.setTitle("Music Assistant Login");
                var layout = new android.widget.LinearLayout(activity);
                layout.setOrientation(1);
                layout.setPadding(60, 20, 60, 0);
                var userField = new android.widget.EditText(activity);
                userField.setHint("Username");
                layout.addView(userField);
                var passField = new android.widget.EditText(activity);
                passField.setHint("Password");
                passField.setInputType(129);
                layout.addView(passField);
                builder.setView(layout);
                builder.setPositiveButton("Login", function(d, w) {
                    var username = userField.getText().toString();
                    var password = passField.getText().toString();
                    bg.run(function() {
                        var loginBody = JSON.stringify({ "credentials": { "username": username, "password": password } });
                        var loginUrl = "http://" + host + ":8095/auth/login";
                        var loginResponse = http.post(loginUrl, loginBody);
                        if (loginResponse && String(loginResponse).indexOf("token") !== -1) {
                            var data = JSON.parse(loginResponse);
                            if (data.token) {
                                storage.set("auth_token", data.token);
                                renderMAUI(host); 
                            }
                        }
                    });
                });
                builder.setNegativeButton("Cancel", null);
                builder.show();
            }

            function showTokenDialog(host) {
                var builder = new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity);
                builder.setTitle("Music Assistant Token");
                var input = new android.widget.EditText(activity);
                input.setHint("Token");
                input.setText(storage.get("auth_token") || "");
                var padding = 60;
                var container = new android.widget.FrameLayout(activity);
                container.setPadding(padding, 20, padding, 0);
                container.addView(input);
                builder.setView(container);
                builder.setPositiveButton("Save", function() {
                    var token = input.getText().toString().trim();
                    if (token) {
                        storage.set("auth_token", token);
                        renderMAUI(host);
                    }
                });
                builder.setNeutralButton("Clear", function() {
                    storage.set("auth_token", "");
                    renderMAUI(host);
                });
                builder.show();
            }
            
            // Register external configuration call
            events.onConfigRequested(function() {
                var host = storage.get("last_host") || "127.0.0.1";
                var builder = new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity);
                builder.setTitle("Music Assistant Setup");
                builder.setMessage("Choose how you want to authenticate with the server.");
                builder.setPositiveButton("Login", function() { showLoginDialog(host); });
                builder.setNeutralButton("Token", function() { showTokenDialog(host); });
                builder.show();
            });
        """.trimIndent()

        val jsonFile = File(dir, "music_assistant.json")
        val jsFile = File(dir, "music_assistant.js")
        jsonFile.writeText(maJson)
        jsFile.writeText(maScript)
    }

    fun getPlugins(): List<Plugin> {
        val plugins = mutableListOf<Plugin>()
        pluginsDir.listFiles { _, name -> name.endsWith(".json") }?.forEach { file ->
            try {
                val plugin = Plugin.fromJson(file.readText())
                plugin.isEnabled = sharedPreferences.getBoolean(plugin.id, false)
                plugins.add(plugin)
            } catch (e: Exception) {
                Log.e("PluginManager", "Error loading plugin metadata: ${file.name}", e)
            }
        }
        return plugins
    }

    fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        sharedPreferences.edit().putBoolean(pluginId, enabled).apply()
    }

    fun runEnabledPlugins(activity: MainActivity, service: AudioCastService?) {
        getPlugins().filter { it.isEnabled }.forEach { plugin ->
            runPlugin(plugin, activity, service)
        }
    }
    
    fun requestPluginConfig(pluginId: String, activity: android.app.Activity) {
        val plugin = getPlugins().find { it.id == pluginId } ?: return
        runPlugin(plugin, activity, null, isConfigOnly = true)
    }

    private fun runPlugin(plugin: Plugin, activity: android.app.Activity, initialService: AudioCastService?, isConfigOnly: Boolean = false) {
        val scriptFile = File(pluginsDir, plugin.scriptPath)
        if (!scriptFile.exists()) return

        Thread {
            val rhino = RhinoContext.enter()
            rhino.optimizationLevel = -1
            try {
                val scope = rhino.initStandardObjects()
                
                ScriptableObject.putProperty(scope, "activity", RhinoContext.javaToJS(activity, scope))
                ScriptableObject.putProperty(scope, "context", RhinoContext.javaToJS(context, scope))
                ScriptableObject.putProperty(scope, "service", RhinoContext.javaToJS(initialService, scope))
                
                val uiHelper = object {
                    fun run(f: Runnable) = activity.runOnUiThread {
                        val cx = RhinoContext.enter()
                        try {
                            cx.optimizationLevel = -1
                            f.run()
                        } finally {
                            RhinoContext.exit()
                        }
                    }
                    fun clear() = activity.runOnUiThread { 
                        if (activity is MainActivity) {
                            activity.pluginContainer.removeAllViews() 
                            activity.pluginContainer.visibility = View.GONE
                        }
                    }
                    fun inflate(layoutName: String): View? {
                        val id = activity.resources.getIdentifier(layoutName, "layout", activity.packageName)
                        if (id != 0 && activity is MainActivity) {
                            val view = activity.layoutInflater.inflate(id, activity.pluginContainer, false)
                            return view
                        }
                        return null
                    }
                    fun findView(parent: View, name: String): View? {
                        val id = activity.resources.getIdentifier(name, "id", activity.packageName)
                        return if (id != 0) parent.findViewById(id) else null
                    }
                    fun add(view: View) {
                        if (activity is MainActivity) {
                            activity.pluginContainer.addView(view)
                            activity.pluginContainer.visibility = View.VISIBLE
                        }
                    }
                }
                ScriptableObject.putProperty(scope, "ui", RhinoContext.javaToJS(uiHelper, scope))

                val bgHelper = object {
                    fun run(f: Runnable) {
                        Thread {
                            val cx = RhinoContext.enter()
                            try {
                                cx.optimizationLevel = -1
                                f.run()
                            } catch (e: Exception) {
                                Log.e("PluginBG", "Error in background task", e)
                            } finally {
                                RhinoContext.exit()
                            }
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
                    private var configCallback: org.mozilla.javascript.Function? = null
                    
                    fun onServiceConnected(callback: org.mozilla.javascript.Function) {
                        if (isConfigOnly) return
                        if (activity !is MainActivity) return
                        activity.lifecycleScope.launch(Dispatchers.IO) {
                            activity.audioCastServiceFlow.collectLatest { s ->
                                if (s != null) {
                                    val cx = RhinoContext.enter()
                                    try {
                                        cx.optimizationLevel = -1
                                        ScriptableObject.putProperty(scope, "service", RhinoContext.javaToJS(s, scope))
                                        callback.call(cx, scope, scope, arrayOf(RhinoContext.javaToJS(s, scope)))
                                    } finally {
                                        RhinoContext.exit()
                                    }
                                } else {
                                    activity.runOnUiThread { uiHelper.clear() }
                                }
                            }
                        }
                    }

                    fun onStateChanged(callback: org.mozilla.javascript.Function) {
                        if (isConfigOnly) return
                        if (activity !is MainActivity) return
                        activity.lifecycleScope.launch(Dispatchers.IO) {
                            activity.audioCastServiceFlow.filterNotNull().collectLatest { s ->
                                s.state.collectLatest { state ->
                                    val cx = RhinoContext.enter()
                                    try {
                                        cx.optimizationLevel = -1
                                        callback.call(cx, scope, scope, arrayOf(state.name))
                                    } finally {
                                        RhinoContext.exit()
                                    }
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
                            } finally {
                                RhinoContext.exit()
                            }
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
                        val request = Request.Builder().url(urlStr).build()
                        client.newWebSocket(request, object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                if (tokenStr != null && tokenStr != "null" && tokenStr.isNotEmpty()) {
                                    webSocket.send(JSONObject().apply {
                                        put("message_id", "auth-123")
                                        put("command", "auth")
                                        put("args", JSONObject().apply { put("token", tokenStr) })
                                    }.toString())
                                } else { sendCmd(webSocket) }
                            }
                            private fun sendCmd(webSocket: WebSocket) {
                                val msg = JSONObject()
                                msg.put("command", cmdStr)
                                msg.put("message_id", "plugin_cmd")
                                msg.put("args", if (args != null && args != "null" && args.isNotEmpty()) JSONObject(args) else JSONObject())
                                webSocket.send(msg.toString())
                            }
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                try {
                                    val json = JSONObject(text)
                                    val msgId = json.optString("message_id")
                                    if (msgId == "auth-123") {
                                        if (json.optJSONObject("result")?.optBoolean("authenticated") == true) sendCmd(webSocket)
                                        else { result = "Error: Auth Failed"; webSocket.close(1000, "Auth Failed"); latch.countDown() }
                                    } else if (msgId == "plugin_cmd") {
                                        result = if (json.has("error_code")) "Error: ${json.optString("details")}" else json.opt("result")?.toString() ?: text
                                        webSocket.close(1000, "Done"); latch.countDown()
                                    }
                                } catch (e: Exception) {}
                            }
                            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { result = "Error: ${t.message}"; latch.countDown() }
                            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { latch.countDown() }
                        })
                        if (!latch.await(15, TimeUnit.SECONDS)) result = result ?: "Error: Timeout"
                        return result
                    }
                }
                ScriptableObject.putProperty(scope, "ws", RhinoContext.javaToJS(wsHelper, scope))

                val networkHelper = object {
                    fun fetch(url: Any?): String? = fetch(url, null)
                    fun fetch(url: Any?, headersJson: Any?): String? = try { 
                        val urlStr = url?.toString() ?: return "Error: Null URL"
                        val conn = URL(urlStr).openConnection() as HttpURLConnection
                        conn.requestMethod = "GET"
                        conn.connectTimeout = 5000
                        conn.readTimeout = 5000
                        conn.setRequestProperty("Accept", "application/json")
                        conn.setRequestProperty("User-Agent", "AriaCast-Plugin/1.0")
                        conn.setRequestProperty("Host", URL(urlStr).host)
                        headersJson?.toString()?.let { if (it != "null" && it.isNotEmpty()) { val json = JSONObject(it); json.keys().forEach { key -> conn.setRequestProperty(key, json.getString(key)) } } }
                        if (conn.responseCode in 200..299) conn.inputStream.bufferedReader(Charsets.UTF_8).readText() else "Error: ${conn.responseCode}"
                    } catch (e: Exception) { "Error: ${e.message}" }
                    fun post(url: Any?): String? = post(url, null, null)
                    fun post(url: Any?, body: Any?): String? = post(url, body, null)
                    fun post(url: Any?, body: Any?, headersJson: Any?): String? = try {
                        val urlStr = url?.toString() ?: return "Error: Null URL"
                        val bodyStr = body?.toString() ?: ""
                        val conn = URL(urlStr).openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.doOutput = true
                        conn.connectTimeout = 5000
                        conn.readTimeout = 5000
                        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        conn.setRequestProperty("Accept", "application/json")
                        conn.setRequestProperty("User-Agent", "AriaCast-Plugin/1.0")
                        conn.setRequestProperty("Host", URL(urlStr).host)
                        headersJson?.toString()?.let { if (it != "null" && it.isNotEmpty()) { val json = JSONObject(it); json.keys().forEach { key -> conn.setRequestProperty(key, json.getString(key)) } } }
                        val bytes = bodyStr.toByteArray(Charsets.UTF_8)
                        conn.setFixedLengthStreamingMode(bytes.size)
                        conn.outputStream.use { it.write(bytes) }
                        if (conn.responseCode in 200..299) conn.inputStream.bufferedReader(Charsets.UTF_8).readText() else "Error: ${conn.responseCode}"
                    } catch (e: Exception) { "Error: ${e.message}" }
                }
                ScriptableObject.putProperty(scope, "http", RhinoContext.javaToJS(networkHelper, scope))

                val logger = object {
                    fun info(msg: String) = Log.i("Plugin:${plugin.name}", msg)
                    fun error(msg: String) = Log.e("Plugin:${plugin.name}", msg)
                    fun warn(msg: String) = Log.w("Plugin:${plugin.name}", msg)
                }
                ScriptableObject.putProperty(scope, "console", RhinoContext.javaToJS(logger, scope))

                rhino.evaluateString(scope, scriptFile.readText(), plugin.name, 1, null)
            } catch (e: Exception) {
                Log.e("PluginManager", "Error executing plugin: ${plugin.name}", e)
            } finally {
                RhinoContext.exit()
            }
        }.start()
    }
}
