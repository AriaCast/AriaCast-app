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

/**
 * A known behavior of one family of speakers, with a plain-language note for the connection
 * doctor. Entries are added only for behavior that is documented in an issue or in the code
 * that works around it; "report my speaker" in the doctor exists to find the next ones.
 */
data class Quirk(
    val id: String,
    @param:StringRes val note: Int,
    val appliesTo: (DeviceIdentity) -> Boolean,
)

object DeviceQuirks {
    private fun DeviceIdentity.mentions(vararg needles: String): Boolean {
        val haystack = listOfNotNull(name, model, manufacturer).joinToString(" ").lowercase()
        return needles.any { haystack.contains(it) }
    }

    val ALL: List<Quirk> = listOf(
        // Issue #50: SetAVTransportURI leaves a WiiM paused at 0:00. AriaCast now sends Play
        // after every track change for all DLNA renderers; the note explains it if it recurs.
        Quirk("wiim-dlna-restart", R.string.quirk_wiim_dlna) { it.platform == "DLNA" && it.mentions("wiim", "linkplay") },
        // AirPlay 2 receivers built on shairport-sync + nqptp only accept PTP timing, which
        // Android can't offer without root (see PtpMaster): AriaCast uses NTP timing or AirPlay 1.
        Quirk("shairport-airplay2-ptp", R.string.quirk_shairport_ptp) {
            it.platform == "AirPlay2" && it.mentions("shairport")
        },
    )

    /** Every known behavior that applies to [device]. */
    fun matching(device: DeviceIdentity): List<Quirk> = ALL.filter { it.appliesTo(device) }
}
