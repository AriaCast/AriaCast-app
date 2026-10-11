package com.aria.ariacast.airplay

import java.io.IOException

/** The receiver needs a secret from the user before it will accept a stream. */
class AirPlayAuthRequiredException(val kind: Kind, val host: String, message: String) : IOException(message) {
    enum class Kind {
        /** A password configured on the receiver (AirPlay 1 Digest / AirPlay 2 access control). */
        PASSWORD,
        /** A one-time code shown on the receiver's screen, used to pair once. */
        PIN
    }
}

/** The receiver is already playing from another sender (RTSP 453). */
class AirPlayBusyException(host: String) : IOException("$host is busy with another sender")

/** The receiver can't be driven with the timing protocols we can offer (AirPlay 2 only). */
class AirPlayTimingUnsupportedException(message: String) : IOException(message)

/** Callbacks a running AirPlay session reports through. */
interface AirPlaySessionListener {
    /** The receiver changed its own volume (remote, buttons, Home app). */
    fun onRemoteVolume(db: Double) {}

    /** A transport/remote command from the receiver ("play", "pause", "nextitem"...). */
    fun onRemoteCommand(command: String) {}

    /** The session ended without us asking (receiver went away / took another sender). */
    fun onDisconnected(reason: String) {}
}
