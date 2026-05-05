package com.aria.ariacast

data class Server(
    val name: String,
    val host: String,
    val port: Int,
    val version: String,
    val codecs: List<String>,
    val sampleRate: Int,
    val channels: Int,
    val platform: String? = null,
    val extra: String? = null // For DLNA control URL or other data
)
