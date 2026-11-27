package com.aria.ariacast.quirks

import java.util.Locale
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How a speaker behaves over one protocol. */
enum class Verdict(val id: String, val label: String) {
    /** Plays as expected. */
    GOOD("good", "Works"),

    /** Plays, with a limit the user should know about. */
    PARTIAL("partial", "Works with a catch"),

    /** Does not play, or breaks in a way the app cannot work around. */
    BAD("bad", "Does not work"),

    /** Has no local way in that AriaCast offers (Bluetooth only, cloud only). */
    UNSUPPORTED("unsupported", "Not supported"),
    ;

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/**
 * Settings a speaker needs that differ from the usual ones. Every field is optional; null means
 * "do what AriaCast normally does". The set is closed on purpose: the list is a public file, so
 * it can only turn the knobs below, within the limits checked by [KnownSpeakersFile.parse].
 */
data class SpeakerConfig(
    /** DLNA: send Play after every track change. On by default. */
    val dlnaPlayAfterSetUri: Boolean? = null,
    /** DLNA: content type of the stream, for renderers that refuse the other one. */
    val dlnaContentType: String? = null,
    /** DLNA: skip the Stop sent before the stream address, for renderers that drop out on it. */
    val dlnaSkipInitialStop: Boolean? = null,
    /** Delay in ms applied the first time this speaker is used, until the user sets their own. */
    val defaultDelayMs: Int? = null,
    /** Send track titles and artwork to this speaker. On by default. */
    val sendMetadata: Boolean? = null,
) {
    /** This config, with the fields it leaves unset taken from [other]. */
    fun orElse(other: SpeakerConfig) = SpeakerConfig(
        dlnaPlayAfterSetUri = dlnaPlayAfterSetUri ?: other.dlnaPlayAfterSetUri,
        dlnaContentType = dlnaContentType ?: other.dlnaContentType,
        dlnaSkipInitialStop = dlnaSkipInitialStop ?: other.dlnaSkipInitialStop,
        defaultDelayMs = defaultDelayMs ?: other.defaultDelayMs,
        sendMetadata = sendMetadata ?: other.sendMetadata,
    )

    companion object {
        val DLNA_CONTENT_TYPES = setOf("audio/wav", "audio/x-wav")
        val DELAY_RANGE = 0..2000
    }
}

/** Which speakers an entry is about: this protocol, and a name, model or maker containing one of these words. */
data class Match(val platform: String?, val mentions: List<String>) {
    fun appliesTo(device: DeviceIdentity): Boolean {
        if (platform != null && device.platform != platform) return false
        val haystack = listOfNotNull(device.name, device.model, device.manufacturer).joinToString(" ").lowercase()
        return mentions.any { haystack.contains(it) }
    }
}

/**
 * One entry of the list of speakers we know something about (public as data/speakers.json and
 * docs/SPEAKERS.md). Entries are added only for behavior with evidence: an issue, a maintainer's
 * own test, or the code that works around it.
 */
data class KnownSpeaker(
    val id: String,
    /** Brand and, where known, the model it was seen on. */
    val speaker: String,
    val protocol: String,
    val verdict: Verdict,
    val match: Match,
    /** What a user sees when it goes wrong, in their words. */
    val symptoms: List<String> = emptyList(),
    val whatToDo: String? = null,
    /** First version where this holds (for a fix), if it is tied to one. */
    val since: String? = null,
    /** Where this comes from. */
    val evidence: String,
    /** Message the connection doctor shows while casting to a matching speaker, by language. */
    val notes: Map<String, String> = emptyMap(),
    val config: SpeakerConfig = SpeakerConfig(),
) {
    fun appliesTo(device: DeviceIdentity) = match.appliesTo(device)

    /** The doctor's message in the user's language, falling back to English; null if there is none. */
    fun noteFor(locale: Locale): String? = notes[locale.language] ?: notes["en"]
}

/** The list as loaded: the entries that passed validation, and why the others did not. */
data class KnownSpeakersFile(
    val version: Int,
    val updated: String,
    val speakers: List<KnownSpeaker>,
    val skipped: List<String> = emptyList(),
) {
    companion object {
        const val SCHEMA = 1
        const val MAX_BYTES = 256 * 1024
        const val MAX_ENTRIES = 500

        private val json = Json { ignoreUnknownKeys = true }
        private val idPattern = Regex("[a-z0-9][a-z0-9-]{2,63}")
        private val languagePattern = Regex("[a-z]{2}")

        /**
         * Reads the list. The file is public and changes what the app does, so it is treated as
         * untrusted: a file that is too big, of another schema or not valid JSON is refused whole
         * (null); inside a good file, an entry that breaks a rule is skipped on its own, and a
         * config value outside its limits is dropped rather than clamped.
         */
        fun parse(text: String): KnownSpeakersFile? {
            if (text.length > MAX_BYTES) return null
            val raw = try {
                json.decodeFromString<Raw>(text)
            } catch (_: SerializationException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (raw.schema != SCHEMA || raw.version < 0) return null
            val skipped = mutableListOf<String>()
            val seen = mutableSetOf<String>()
            val entries = raw.speakers.take(MAX_ENTRIES).mapNotNull { e ->
                val id = e.id.trim()
                val problem = when {
                    !idPattern.matches(id) -> "bad id"
                    !seen.add(id) -> "duplicate id"
                    Verdict.of(e.verdict) == null -> "unknown verdict"
                    e.speaker.isBlank() || e.protocol.isBlank() -> "no speaker or protocol"
                    cleanMentions(e.match.mentions).isEmpty() -> "needs at least one word to match"
                    e.evidence.isBlank() -> "no evidence"
                    else -> null
                }
                if (problem != null) {
                    skipped += "${id.ifBlank { "?" }}: $problem"
                    return@mapNotNull null
                }
                KnownSpeaker(
                    id = id,
                    speaker = clean(e.speaker, 80),
                    protocol = clean(e.protocol, 40),
                    verdict = Verdict.of(e.verdict)!!,
                    match = Match(e.match.platform?.let { clean(it, 20) }?.takeIf { it.isNotEmpty() }, cleanMentions(e.match.mentions)),
                    symptoms = e.symptoms.take(8).map { clean(it, 240) }.filter { it.isNotEmpty() },
                    whatToDo = e.whatToDo?.let { clean(it, 320) }?.takeIf { it.isNotEmpty() },
                    since = e.since?.let { clean(it, 24) }?.takeIf { it.isNotEmpty() },
                    evidence = clean(e.evidence, 320),
                    notes = e.notes.filterKeys { languagePattern.matches(it) }.mapValues { clean(it.value, 700) }.filterValues { it.isNotEmpty() },
                    config = e.config?.let(::validConfig) ?: SpeakerConfig(),
                )
            }
            return KnownSpeakersFile(raw.version, clean(raw.updated, 24), entries, skipped)
        }

        private fun validConfig(c: RawConfig) = SpeakerConfig(
            dlnaPlayAfterSetUri = c.dlnaPlayAfterSetUri,
            dlnaContentType = c.dlnaContentType?.takeIf { it in SpeakerConfig.DLNA_CONTENT_TYPES },
            dlnaSkipInitialStop = c.dlnaSkipInitialStop,
            defaultDelayMs = c.defaultDelayMs?.takeIf { it in SpeakerConfig.DELAY_RANGE },
            sendMetadata = c.sendMetadata,
        )

        /** Words to look for: lowercase, 3 to 40 characters, at most 8. Shorter ones would match far too much. */
        private fun cleanMentions(words: List<String>) =
            words.map { clean(it, 40).lowercase() }.filter { it.length >= 3 }.distinct().take(8)

        /** Trimmed, control characters removed, cut to [max]. */
        private fun clean(text: String, max: Int) =
            text.filter { it >= ' ' && it != '\u007f' }.trim().take(max)
    }

    @Serializable
    private data class Raw(
        val schema: Int = 0,
        val version: Int = 0,
        val updated: String = "",
        val speakers: List<RawEntry> = emptyList(),
    )

    @Serializable
    private data class RawEntry(
        val id: String = "",
        val speaker: String = "",
        val protocol: String = "",
        val verdict: String = "",
        val match: RawMatch = RawMatch(),
        val symptoms: List<String> = emptyList(),
        val whatToDo: String? = null,
        val since: String? = null,
        val evidence: String = "",
        val notes: Map<String, String> = emptyMap(),
        val config: RawConfig? = null,
    )

    @Serializable
    private data class RawMatch(val platform: String? = null, val mentions: List<String> = emptyList())

    @Serializable
    private data class RawConfig(
        val dlnaPlayAfterSetUri: Boolean? = null,
        val dlnaContentType: String? = null,
        val dlnaSkipInitialStop: Boolean? = null,
        val defaultDelayMs: Int? = null,
        val sendMetadata: Boolean? = null,
    )
}
