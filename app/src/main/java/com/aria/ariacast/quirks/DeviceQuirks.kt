package com.aria.ariacast.quirks

import androidx.annotation.StringRes
import com.aria.ariacast.ExtraFields
import com.aria.ariacast.R

/**
 * What discovery learned about a speaker. [model] and [manufacturer] come from the device itself
 * (mDNS TXT records, the UPnP description), so they are untrusted text: only ever compared,
 * and shown as text.
 */
data class DeviceIdentity(
    val platform: String?,
    val name: String,
    val model: String? = null,
    val manufacturer: String? = null,
) {
    companion object {
        /** Reads the fields discovery packs into a destination's `extra` string. */
        fun of(platform: String?, name: String, extra: String?): DeviceIdentity {
            val fields = ExtraFields.parse(extra)
            return DeviceIdentity(platform, name, fields["model"], fields["manufacturer"])
        }
    }

    /** One line to paste in an issue so a speaker's behavior can be told apart from a bug. */
    fun reportLine(): String = listOfNotNull(
        platform?.let { "protocol=$it" },
        manufacturer?.let { "manufacturer=$it" },
        model?.let { "model=$it" },
        "name=$name",
    ).joinToString(", ")
}

/** How a speaker behaves over one protocol. */
enum class Verdict(val label: String) {
    /** Plays as expected. */
    GOOD("Works"),

    /** Plays, with a limit the user should know about. */
    PARTIAL("Works with a catch"),

    /** Does not play, or breaks in a way the app cannot work around. */
    BAD("Does not work"),
}

/**
 * One entry of the list of speakers we know something about (published as docs/SPEAKERS.md; a
 * unit test keeps the two in step). Entries are added only for behavior with evidence: an issue,
 * a maintainer's own test, or the code that works around it. "Report speaker" in the app exists
 * to find the next ones.
 */
data class KnownSpeaker(
    val id: String,
    /** Brand and, where known, the model it was seen on. */
    val speaker: String,
    val protocol: String,
    val verdict: Verdict,
    /** What a user sees when it goes wrong, in their words. */
    val symptoms: List<String> = emptyList(),
    val whatToDo: String? = null,
    /** First version where this holds (for a fix), if it is tied to one. */
    val since: String? = null,
    /** Where this comes from. */
    val evidence: String,
    /** Message the connection doctor shows while casting to a matching speaker, if any. */
    @param:StringRes val note: Int? = null,
    val appliesTo: (DeviceIdentity) -> Boolean,
)

object DeviceQuirks {
    private const val ISSUES = "https://github.com/AriaCast/AriaCast-app/issues"

    private fun DeviceIdentity.mentions(vararg needles: String): Boolean {
        val haystack = listOfNotNull(name, model, manufacturer).joinToString(" ").lowercase()
        return needles.any { haystack.contains(it) }
    }

    val ALL: List<KnownSpeaker> = listOf(
        KnownSpeaker(
            id = "sonos-airplay2",
            speaker = "Sonos (tested on Play:5)",
            protocol = "AirPlay 2",
            verdict = Verdict.GOOD,
            symptoms = listOf("On 1.1.9 the AirPlay 2 stream did not play, while DLNA to the same speaker did."),
            whatToDo = "Update. Until then, cast to it over DLNA.",
            since = "1.1.9_hotfix",
            evidence = "[#48]($ISSUES/48), confirmed by the reporter after the hotfix",
        ) { it.platform == "AirPlay2" && it.mentions("sonos") },
        KnownSpeaker(
            id = "sonos-dlna",
            speaker = "Sonos (tested on Play:5)",
            protocol = "DLNA",
            verdict = Verdict.GOOD,
            evidence = "[#48]($ISSUES/48), reporter: DLNA was working",
        ) { it.platform == "DLNA" && it.mentions("sonos") },
        KnownSpeaker(
            id = "wiim-dlna-restart",
            speaker = "WiiM (tested on Pro Plus)",
            protocol = "DLNA",
            verdict = Verdict.GOOD,
            symptoms = listOf(
                "The track title changes but playback stays paused at 0:00 after every track change.",
                "Pressing play in the WiiM app starts it again.",
            ),
            whatToDo = "Update: AriaCast now sends Play after every track change. If it still stops, press play once in the WiiM app and report it.",
            since = "2.0.0",
            evidence = "[#50]($ISSUES/50), fixed in the 2.0 line; the reporter's confirmation is not recorded",
            note = R.string.quirk_wiim_dlna,
        ) { it.platform == "DLNA" && it.mentions("wiim", "linkplay") },
        KnownSpeaker(
            id = "shairport-airplay1",
            speaker = "shairport-sync",
            protocol = "AirPlay 1",
            verdict = Verdict.GOOD,
            evidence = "Maintainer's own test in [#48]($ISSUES/48); the AirPlay version is not stated there, so it is listed under AirPlay 1",
        ) { it.platform == "AirPlay" && it.mentions("shairport") },
        KnownSpeaker(
            id = "shairport-airplay2-ptp",
            speaker = "shairport-sync",
            protocol = "AirPlay 2",
            verdict = Verdict.PARTIAL,
            symptoms = listOf("The speaker accepts the connection but timing is off, or AriaCast drops to AirPlay 1."),
            whatToDo = "Nothing to do: AriaCast tries NTP timing and falls back to AirPlay 1 on its own. Real PTP needs root.",
            evidence = "Code: PtpMaster (shairport-sync with nqptp accepts only PTP timing)",
            note = R.string.quirk_shairport_ptp,
        ) { it.platform == "AirPlay2" && it.mentions("shairport") },
        KnownSpeaker(
            id = "appletv-airplay-video-pairing",
            speaker = "Apple TV",
            protocol = "AirPlay (video)",
            verdict = Verdict.PARTIAL,
            symptoms = listOf("On Apple TVs that require pairing, casting a video fails with \"This AirPlay device requires pairing for video\". Audio casting is not affected."),
            whatToDo = "Cast audio only. Video works with Apple TVs that allow unpaired access.",
            evidence = "Roadmap status table and VideoTargets.kt",
        ) { (it.platform == "AirPlay" || it.platform == "AirPlay2") && it.mentions("appletv") },
    )

    /** Every known entry that applies to [device]. */
    fun matching(device: DeviceIdentity): List<KnownSpeaker> = ALL.filter { it.appliesTo(device) }

    /** The entries the connection doctor should explain while casting to [device]. */
    fun notesFor(device: DeviceIdentity): List<KnownSpeaker> = matching(device).filter { it.note != null }
}
