package com.aria.ariacast.receiver

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ReceiverMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    @SerialName("duration_ms") val durationMs: Long = 0,
    @SerialName("position_ms") val positionMs: Long = 0,
    @SerialName("is_playing") val isPlaying: Boolean = false,
) {
    /** Applies a partial update; returns the merged metadata and whether the song changed. */
    fun merge(u: MetadataUpdate): Pair<ReceiverMetadata, Boolean> {
        val songChanged = u.title != null && u.title != title
        return copy(
            title = u.title ?: title,
            artist = u.artist ?: artist,
            album = u.album ?: album,
            artworkUrl = u.artworkUrl ?: artworkUrl,
            durationMs = u.durationMs ?: durationMs,
            positionMs = u.positionMs ?: positionMs,
            isPlaying = u.isPlaying ?: isPlaying,
        ) to songChanged
    }
}

/** A partial metadata update from a sender: null fields keep their current value. */
data class MetadataUpdate(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long? = null,
    val positionMs: Long? = null,
    val isPlaying: Boolean? = null,
)

@Serializable
data class HandshakeResponse(
    val status: String,
    @SerialName("sample_rate") val sampleRate: Int,
    val channels: Int,
    @SerialName("frame_size") val frameSize: Int,
)

@Serializable
data class ControlMessage(
    val command: String? = null,
    val action: String? = null,
    val value: Int? = null,
    @SerialName("position_ms") val positionMs: Long? = null,
)
