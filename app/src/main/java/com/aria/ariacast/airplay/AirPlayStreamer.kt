package com.aria.ariacast.airplay

/** What the casting service needs from a connected AirPlay 1 or AirPlay 2 session. */
interface AirPlayStreamer {
    val host: String

    /** Feeds 44.1 kHz little-endian s16 stereo PCM. */
    fun writePcm(pcm: ByteArray)

    /** Absolute receiver volume in AirPlay dB (-30..0, -144 = mute). */
    fun setVolume(db: Double)

    fun setMetadata(title: String?, artist: String?, album: String?, durationMs: Long?)

    fun setArtwork(image: ByteArray, mimeType: String = "image/jpeg")

    fun setProgress(positionMs: Long, durationMs: Long)

    /** Drops audio the receiver has buffered (e.g. on pause/seek) so it stops immediately. */
    fun flush()

    /** Extra delay for multiroom alignment. */
    var extraDelayMs: Int

    /** Asks the receiver to end the session. Best effort; safe to call more than once. */
    fun teardown()

    /** Releases sockets and threads. */
    fun close()

    fun statsSummary(): String
}

/** Identity the app presents to every AirPlay receiver; persisted by the service. */
data class AirPlaySenderIdentity(
    /** 6-byte MAC-style id, "AA:BB:CC:DD:EE:FF". */
    val deviceId: String,
    /** 64-bit DACP id, 16 upper-case hex characters. */
    val dacpId: String,
    /** Active-Remote token receivers must echo back on DACP requests. */
    val activeRemote: String,
    /** Human readable sender name shown on receivers. */
    val name: String,
    /** Model string ("Android", device model...). */
    val model: String,
    /** 32-byte Ed25519 seed - our long-term pairing identity. */
    val pairingSeed: ByteArray,
    /** Stable pairing identifier sent during pair-setup (HAP "Identifier"). */
    val pairingId: String
) {
    val deviceIdHex: String get() = deviceId.replace(":", "").uppercase()
}
