package com.aria.ariacast.quirks

import android.content.SharedPreferences

/**
 * The question asked once about each speaker, after a minute of casting to it: does it work?
 * What to ask and when lives here so it can be tested; showing it is [com.aria.ariacast.ui.SpeakerFeedbackActivity].
 */
object SpeakerFeedback {
    /** How long a speaker must have been playing before it is asked about. */
    const val ASK_AFTER_MS = 60_000L

    /**
     * A speaker is told apart by its protocol and the name it goes by. An address changes with the
     * router; a name seldom does.
     */
    fun key(platform: String?, name: String) = "${platform ?: "-"}|${name.trim().lowercase()}"

    /**
     * Only things that are speakers are asked about. An AriaCast receiver, a Snapcast or VBAN server
     * or a Sendspin server is software on a phone, a computer or a server: there is no speaker to
     * judge, and a list of speakers has no place for it.
     */
    fun isAskable(platform: String?) = platform in ASKABLE

    private val ASKABLE = setOf("AirPlay", "AirPlay2", "DLNA", "Google Cast")

    /** Ask when the minute is up and the speaker is still playing in a cast, and was never asked. */
    fun shouldAsk(casting: Boolean, stillInCast: Boolean, alreadyAsked: Boolean, platform: String?) =
        casting && stillInCast && !alreadyAsked && isAskable(platform)

    /**
     * A cast that fails in the first minute: ask whether to report it, but only when the speaker is
     * the only one in the cast, since a failure does not say which of several speakers it was.
     */
    fun shouldAskAfterFailure(speakersInCast: Int, alreadyAsked: Boolean, platform: String?) =
        speakersInCast == 1 && !alreadyAsked && isAskable(platform)

    /**
     * After "it works": offer to add the speaker to the public list unless the list already says it
     * is supported ([best] is the entry the speaker's report would be counted in, if any). One more "it works" for a speaker everybody knows adds little, and sending it
     * takes a GitHub account.
     */
    fun offerSharingAfterWorks(best: KnownSpeaker?) = best?.status != Status.SUPPORTED

    /** The word of the report form's dropdown for a status. */
    fun formLabel(status: Status) = when (status) {
        Status.SUPPORTED -> SpeakerIssue.STATUS_SUPPORTED
        Status.QUIRKS -> SpeakerIssue.STATUS_QUIRKS
        Status.UNSUPPORTED -> SpeakerIssue.STATUS_UNSUPPORTED
    }
}

/**
 * What has been asked and answered, kept on the phone. Nothing here is sent anywhere: an answer
 * only leaves the phone if the user then opens a report.
 */
class FeedbackLedger(private val read: (String) -> String?, private val write: (String, String) -> Unit) {
    fun asked(key: String) = read("asked.$key") != null

    fun markAsked(key: String, atMs: Long) = write("asked.$key", atMs.toString())

    fun record(key: String, status: Status) = write("answer.$key", status.id)

    fun answer(key: String): Status? = Status.of(read("answer.$key"))

    companion object {
        fun of(prefs: SharedPreferences) = FeedbackLedger(
            read = { prefs.getString("fb.$it", null) },
            write = { k, v -> prefs.edit().putString("fb.$k", v).apply() },
        )
    }
}
