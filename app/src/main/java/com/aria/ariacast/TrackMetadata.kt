package com.aria.ariacast

import kotlinx.serialization.Serializable

@Serializable
data class TrackMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    val artworkUrl: String?,
    val durationMs: Long?,
    val positionMs: Long?,
    val isPlaying: Boolean
)
