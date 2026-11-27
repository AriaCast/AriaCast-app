package com.aria.ariacast.quirks

import java.net.URLEncoder

/**
 * The address of a pre-filled "Speaker report" issue on GitHub. The field names are the ids in
 * .github/ISSUE_TEMPLATE/speaker-report.yml, and the dropdown values are its option labels.
 */
object SpeakerIssue {
    private const val NEW_ISSUE = "https://github.com/AriaCast/AriaCast-app/issues/new"
    const val TEMPLATE = "speaker-report.yml"

    /** A browser refuses very long addresses, and GitHub's limit is lower than most. */
    const val MAX_URL = 6000

    val VERDICT_WORKS = "Works"
    val VERDICT_CATCH = "Works with a catch"
    val VERDICT_FAILS = "Does not work"

    /** How the app names a protocol, in the words of the form's dropdown. */
    fun protocolLabel(platform: String?): String = when (platform) {
        "AirPlay2" -> "AirPlay 2"
        "AirPlay" -> "AirPlay 1"
        "DLNA" -> "DLNA"
        "Google Cast" -> "Google Cast"
        "Snapcast" -> "Snapcast"
        "Sendspin" -> "Sendspin"
        "AriaCast" -> "AriaCast"
        "VBAN" -> "VBAN"
        null -> "No local protocol"
        else -> "Other"
    }

    /** The name to propose: brand and model if the speaker said them, else what it is called. */
    fun speakerName(identity: DeviceIdentity): String =
        listOfNotNull(identity.manufacturer, identity.model).joinToString(" ").trim().ifEmpty { identity.name }

    fun url(identity: DeviceIdentity, details: String, appVersion: String?, verdict: String? = null): String {
        fun build(details: String): String {
            val fields = listOfNotNull(
                "template" to TEMPLATE,
                "title" to "Speaker: ${speakerName(identity)}",
                "speaker" to speakerName(identity),
                "protocol" to protocolLabel(identity.platform),
                verdict?.let { "verdict" to it },
                "fingerprint" to details,
                appVersion?.let { "version" to it },
            )
            return NEW_ISSUE + "?" + fields.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
        }
        // The details are the long part: cut them rather than lose the address altogether.
        var text = details
        var url = build(text)
        while (url.length > MAX_URL && text.length > 200) {
            text = text.take(text.length * 3 / 4)
            url = build(text)
        }
        return url
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
