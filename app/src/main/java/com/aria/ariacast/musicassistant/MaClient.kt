package com.aria.ariacast.musicassistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class MaPlayer(val id: String, val name: String, val available: Boolean, val playingAriaCast: Boolean, val state: String?)

class MaAuthException : Exception("Music Assistant needs a token")

/**
 * Minimal Music Assistant client (JSON-RPC over `POST /api`), replacing the example_MA
 * plugin: list players and point one of them at the AriaCast Receiver source.
 */
class MaClient(private val host: String, private val token: String?, private val port: Int = 8095) {
    private val base get() = "http://$host:$port"

    suspend fun players(): List<MaPlayer> {
        val arr = command("players/all") as? JSONArray ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { p ->
            val active = p.optString("active_source", "")
            MaPlayer(
                id = p.getString("player_id"),
                name = p.optString("display_name").ifEmpty { p.optString("name", p.getString("player_id")) },
                available = p.optBoolean("available", true),
                playingAriaCast = active.contains("ariacast", ignoreCase = true),
                state = p.optString("state").ifEmpty { null },
            )
        }.sortedBy { it.name.lowercase() }
    }

    /** Selects the AriaCast Receiver plugin source on [playerId]. */
    suspend fun playAriaCastOn(playerId: String) {
        val sourceId = findAriaCastSource() ?: "ariacast"
        command("players/cmd/select_source", JSONObject().put("player_id", playerId).put("source", sourceId))
    }

    private suspend fun findAriaCastSource(): String? {
        (runCatching { command("players/plugin_sources") }.getOrNull() as? JSONArray)?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                if (s.optString("source_id") == "ariacast" || s.optString("domain") == "ariacast" || s.optString("name").contains("AriaCast")) {
                    return s.optString("source_id").ifEmpty { s.optString("id").ifEmpty { s.optString("domain") } }
                }
            }
        }
        (runCatching { command("providers") }.getOrNull() as? JSONArray)?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                if (p.optString("domain") == "ariacast" || p.optString("name").contains("AriaCast")) {
                    return p.optString("instance_id").ifEmpty { p.optString("domain") }
                }
            }
        }
        return null
    }

    suspend fun command(command: String, args: JSONObject = JSONObject()): Any? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("command", command).put("args", args).toString()
        val text = post("$base/api", body, token)
        val parsed = when (val t = text.trim()) {
            "" -> null
            else -> if (t.startsWith("[")) JSONArray(t) else JSONObject(t)
        }
        // Some versions wrap the answer in {"result": ...}
        if (parsed is JSONObject && parsed.has("result")) parsed.get("result") else parsed
    }

    companion object {
        /** Logs in with username/password and returns an access token. */
        suspend fun login(host: String, username: String, password: String, port: Int = 8095): String = withContext(Dispatchers.IO) {
            val body = JSONObject().put("username", username).put("password", password).toString()
            val o = JSONObject(post("http://$host:$port/auth/login", body, null))
            o.optString("access_token").ifEmpty { o.optString("token") }.ifEmpty { throw MaAuthException() }
        }

        private fun post(url: String, body: String, token: String?): String {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 5000
                c.readTimeout = 8000
                c.setRequestProperty("Content-Type", "application/json")
                token?.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Authorization", "Bearer ${it.removePrefix("Bearer ").trim()}") }
                c.outputStream.use { it.write(body.toByteArray()) }
                if (c.responseCode == 401 || c.responseCode == 403) throw MaAuthException()
                if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
                return c.inputStream.bufferedReader().use { it.readText() }
            } finally {
                c.disconnect()
            }
        }

        /** True if a receiver name looks like Music Assistant's AriaCast Receiver. */
        fun isMusicAssistant(name: String): Boolean = name.replace(" ", "").contains("musicassistant", ignoreCase = true)
    }
}
