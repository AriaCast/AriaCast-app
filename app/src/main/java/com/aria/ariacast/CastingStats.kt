package com.aria.ariacast

data class CastingStats(
    val bufferedFrames: Int = 0,
    val droppedFrames: Int = 0,
    val receivedFrames: Int = 0,
    val sentFrames: Long = 0,
    val latency: Long = 0,
    val lastFrameTimestamp: Long = 0
)