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
import kotlinx.coroutines.withContext
import org.json.JSONArray

class PluginManager(private val context: Context) {

    private val sharedPreferences = context.getSharedPreferences("plugins_prefs", Context.MODE_PRIVATE)
    private var activeService: AudioCastService? = null
    private val runningPluginIds = mutableSetOf<String>()
    
    fun getPlugins(): List<Plugin> {
        val plugins = mutableListOf<Plugin>()
        val customPathUri = sharedPreferences.getString("plugin_folder_uri", null)
        if (!customPathUri.isNullOrEmpty()) {
            try {
                val uri = Uri.parse(customPathUri)
                val documentDir = DocumentFile.fromTreeUri(context, uri)
                if (documentDir != null && documentDir.exists()) {
                    scanDocumentDirForPlugins(documentDir, plugins)
                }
            } catch (e: Exception) {
                Log.e("PluginManager", "Error scanning plugins", e)
            }
        }
        return plugins
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
        sharedPreferences.edit()
            .putBoolean(pluginId, enabled)
            .putLong("plugins_updated_at", System.currentTimeMillis())
            .apply()
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

    suspend fun syncPluginsFromGitHub(): Boolean = withContext(Dispatchers.IO) {
        val customPathUri = sharedPreferences.getString("plugin_folder_uri", null) ?: return@withContext false
        val rootUri = Uri.parse(customPathUri)
        val rootDir = DocumentFile.fromTreeUri(context, rootUri) ?: return@withContext false
        
        try {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url("https://api.github.com/repos/AriaCast/AriaCast-android-plugins/contents/")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val body = response.body?.string() ?: return@withContext false
                val jsonArray = JSONArray(body)
                
                for (i in 0 until jsonArray.length()) {
                    val fileObj = jsonArray.getJSONObject(i)
                    val fileName = fileObj.getString("name")
                    val downloadUrl = fileObj.getString("download_url")
                    val type = fileObj.getString("type")
                    
                    if (type == "file" && (fileName.endsWith(".json") || fileName.endsWith(".js"))) {
                        downloadAndSaveFile(downloadUrl, fileName, rootDir)
                    }
                }
            }
            sharedPreferences.edit().putLong("plugins_updated_at", System.currentTimeMillis()).apply()
            return@withContext true
        } catch (e: Exception) {
            Log.e("PluginManager", "Failed to sync plugins", e)
            return@withContext false
        }
    }

    private fun downloadAndSaveFile(url: String, fileName: String, destDir: DocumentFile) {
        try {
            val client = OkHttpClient()
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val content = response.body?.bytes() ?: return
                    var file = destDir.findFile(fileName)
                    if (file == null) {
                        file = destDir.createFile(if (fileName.endsWith(".json")) "application/json" else "application/javascript", fileName)
                    }
                    file?.uri?.let { uri ->
                        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                            outputStream.write(content)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("PluginManager", "Failed to download $fileName", e)
        }
    }
}
