package com.aria.ariacast

import org.json.JSONArray
import org.json.JSONObject

data class CastGroup(
    val name: String,
    val hosts: List<String>
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("name", name)
            put("hosts", JSONArray(hosts))
        }.toString()
    }

    companion object {
        fun fromJson(json: String): CastGroup {
            val obj = JSONObject(json)
            val hostsArray = obj.getJSONArray("hosts")
            val hosts = mutableListOf<String>()
            for (i in 0 until hostsArray.length()) {
                hosts.add(hostsArray.getString(i))
            }
            return CastGroup(obj.getString("name"), hosts)
        }
    }
}
