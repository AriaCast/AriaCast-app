package com.aria.ariacast

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TrackMetadata(
    @SerialName("title") val title: String?,
    @SerialName("artist") val artist: String?,
    @SerialName("album") val album: String?,
    @SerialName("artworkUrl") val artworkUrl: String?,
    @SerialName("durationMs") val durationMs: Long?,
    @SerialName("positionMs") val positionMs: Long?,
    @SerialName("isPlaying") val isPlaying: Boolean
)
