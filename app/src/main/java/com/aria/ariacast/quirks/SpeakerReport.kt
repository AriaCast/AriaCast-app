package com.aria.ariacast.quirks

import com.aria.ariacast.PacketLog

/**
 * The text behind "Report speaker": who the speaker is, what we know about it, and the log lines
 * that concern it, ready to paste into an issue. Pure, so it is unit-tested.
 */
object SpeakerReport {
    /** Newest lines kept, so a long session does not bury the handshake in noise. */
    const val MAX_LOG_LINES = 500

    private val ipv4 = Regex("""\b(\d{1,3})\.(\d{1,3})\.\d{1,3}\.\d{1,3}\b""")

    /** `192.168.1.20` becomes `192.168.x.x`: enough to tell networks apart, not to find a home. */
    fun maskAddresses(text: String): String = ipv4.replace(text) { "${it.groupValues[1]}.${it.groupValues[2]}.x.x" }

    /**
     * The lines about this speaker, oldest first. A line counts if it is tagged with [host], or,
     * for lines logged without a tag (protocol clients), if its text names the host or the speaker.
     */
    fun logFor(logs: List<PacketLog>, host: String, name: String): List<PacketLog> =
        logs.asReversed().filter { line ->
            line.device == host ||
                (host.isNotEmpty() && line.message.contains(host)) ||
                (name.isNotEmpty() && line.message.contains(name))
        }.takeLast(MAX_LOG_LINES)

    class Context(
        val app: String,
        val android: String,
        val device: String,
        /** Cast state and last error, when the service is running. */
        val castState: String? = null,
        /** Delay and sound settings saved for this speaker. */
        val settings: String? = null,
    )

    /** Who the speaker is and what is known about it, without the log. Addresses are shortened. */
    fun summary(
        context: Context,
        identity: DeviceIdentity,
        known: List<KnownSpeaker>,
    ): String = buildString {
        appendLine("App: ${context.app}")
        appendLine("Phone: ${context.device}, Android ${context.android}")
        appendLine("Speaker: ${identity.reportLine()}")
        if (known.isEmpty()) {
            appendLine("Known: nothing recorded for this speaker yet")
        } else {
            known.forEach { appendLine("Known: ${it.speaker}, ${it.protocol}: ${it.verdict.label}") }
        }
        context.castState?.let { appendLine("Cast: $it") }
        context.settings?.let { appendLine("Settings: $it") }
    }.trimEnd().let(::maskAddresses)

    fun build(
        context: Context,
        identity: DeviceIdentity,
        host: String,
        known: List<KnownSpeaker>,
        logs: List<PacketLog>,
    ): String = buildString {
        appendLine("AriaCast speaker report")
        appendLine("Addresses are shortened to their first two parts; speaker names are included.")
        appendLine()
        appendLine(summary(context, identity, known))
        appendLine()
        val lines = logFor(logs, host, identity.name)
        if (lines.isEmpty()) {
            appendLine("No log lines for this speaker (it has not been cast to since the app started).")
        } else {
            appendLine("Log, oldest first (${lines.size} lines):")
            lines.forEach { l ->
                val repeat = if (l.count > 1) " x${l.count}" else ""
                appendLine("${l.timestamp} ${l.direction} ${l.type}$repeat ${l.message}")
            }
        }
    }.let(::maskAddresses)
}
