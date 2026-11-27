package com.aria.ariacast.quirks

import com.aria.ariacast.ExtraFields
import java.util.Locale

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

object DeviceQuirks {
    /** Every entry of the list in use. */
    val all: List<KnownSpeaker> get() = KnownSpeakersStore.current.speakers

    /** Every known entry that applies to [device]. */
    fun matching(device: DeviceIdentity): List<KnownSpeaker> = all.filter { it.appliesTo(device) }

    /** The entries the connection doctor should explain while casting to [device], in [locale]. */
    fun notesFor(device: DeviceIdentity, locale: Locale): List<Pair<KnownSpeaker, String>> =
        matching(device).mapNotNull { k -> k.noteFor(locale)?.let { k to it } }

    /**
     * What this speaker needs that differs from the usual. Where several entries apply, the first
     * one in the list that sets a field decides it.
     */
    fun configFor(device: DeviceIdentity): SpeakerConfig =
        matching(device).fold(SpeakerConfig()) { acc, k -> acc.orElse(k.config) }
}
