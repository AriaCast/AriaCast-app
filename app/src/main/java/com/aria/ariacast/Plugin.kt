package com.aria.ariacast

import android.content.Context
import org.json.JSONObject

data class Plugin(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    val scriptPath: String,
    var isEnabled: Boolean = false
) {
    companion object {
        fun fromJson(json: String): Plugin {
            val obj = JSONObject(json)
            return Plugin(
                obj.getString("id"),
                obj.getString("name"),
                obj.getString("description"),
                obj.getString("version"),
                obj.getString("author"),
                obj.getString("scriptPath")
            )
        }
    }
}
